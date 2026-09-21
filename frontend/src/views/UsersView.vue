<template>
  <div class="users-page">
    <PageHeader
      kicker="SETTINGS"
      title="用户管理"
      description="管理登录账号与角色分配。密码以 BCrypt 加密存储且从不回传前端，仅系统管理员可访问。"
    >
      <template #actions>
        <el-button @click="reload">
          <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>新增用户
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never" class="rc-card">
      <div class="toolbar">
        <el-input
          v-model="keyword"
          placeholder="搜索用户名或显示名"
          clearable
          style="width: 260px"
          @keyup.enter="onSearch"
          @clear="onSearch"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" plain @click="onSearch">搜索</el-button>
      </div>

      <el-skeleton v-if="usersStore.loading && !usersStore.loaded" :rows="5" animated />
      <el-alert
        v-else-if="usersStore.error"
        type="error"
        :title="usersStore.error"
        :closable="false"
        show-icon
      >
        <el-button size="small" type="primary" plain @click="reload">重试</el-button>
      </el-alert>
      <EmptyBlock
        v-else-if="usersStore.records.length === 0"
        icon="UserFilled"
        title="暂无用户"
        description="点击右上角新增用户，为用户分配角色后即可登录系统"
      />
      <template v-else>
        <el-table v-loading="usersStore.loading" :data="usersStore.records" style="width: 100%">
          <el-table-column prop="username" label="用户名" min-width="120" />
          <el-table-column prop="displayName" label="显示名" min-width="120" />
          <el-table-column label="角色" min-width="200">
            <template #default="{ row }">
              <el-tag
                v-for="role in row.roles"
                :key="role"
                size="small"
                :type="roleTagType(role)"
                effect="light"
                class="role-tag"
              >
                {{ roleLabel(role) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="plain">
                {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后登录" min-width="160">
            <template #default="{ row }">
              <span class="dim mono">{{ row.lastLoginAt ? formatDateTime(row.lastLoginAt) : '从未登录' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="230" fixed="right">
            <template #default="{ row }">
              <el-button size="small" @click="openRoles(row)">角色</el-button>
              <el-button size="small" @click="openPassword(row)">改密</el-button>
              <el-button
                size="small"
                :type="row.status === 'ACTIVE' ? 'warning' : 'success'"
                plain
                :loading="usersStore.busyId === row.id"
                @click="handleToggle(row)"
              >
                {{ row.status === 'ACTIVE' ? '停用' : '启用' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager">
          <el-pagination
            layout="total, prev, pager, next"
            :total="usersStore.total"
            :current-page="usersStore.current"
            :page-size="usersStore.size"
            @current-change="onPageChange"
          />
        </div>
      </template>
    </el-card>

    <el-dialog v-model="createVisible" title="新增用户" width="520px" destroy-on-close>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="登录名，仅字母数字与 _ . -" />
        </el-form-item>
        <el-form-item label="显示名" prop="displayName">
          <el-input v-model="createForm.displayName" placeholder="例如 张三" />
        </el-form-item>
        <el-form-item label="初始密码" prop="password">
          <el-input v-model="createForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="租户（可选）" prop="tenantId">
          <el-input v-model="createForm.tenantId" placeholder="留空则为 default" />
        </el-form-item>
        <el-form-item label="角色" prop="roles">
          <el-select v-model="createForm.roles" multiple style="width: 100%" placeholder="选择角色">
            <el-option v-for="opt in ROLE_OPTIONS" :key="opt.code" :label="opt.label" :value="opt.code" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitCreate">新增</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordVisible" title="重置密码" width="440px" destroy-on-close>
      <el-form ref="passwordFormRef" :model="passwordForm" :rules="passwordRules" label-position="top">
        <p class="dialog-hint">
          为用户 <b>{{ targetUser?.displayName }}</b>（{{ targetUser?.username }}）设置新密码
        </p>
        <el-form-item label="新密码" prop="password">
          <el-input v-model="passwordForm.password" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitPassword">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rolesVisible" title="分配角色" width="440px" destroy-on-close>
      <p class="dialog-hint">
        调整用户 <b>{{ targetUser?.displayName }}</b>（{{ targetUser?.username }}）的角色
      </p>
      <el-select v-model="rolesForm.roles" multiple style="width: 100%" placeholder="选择角色">
        <el-option v-for="opt in ROLE_OPTIONS" :key="opt.code" :label="opt.label" :value="opt.code" />
      </el-select>
      <template #footer>
        <el-button @click="rolesVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { toApiError } from '@/api/client'
import type { UserVO } from '@/api/types'
import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useUsersStore } from '@/stores/users'
import { formatDateTime } from '@/utils/format'

type RoleTagType = 'danger' | 'warning' | 'success'

const ROLE_OPTIONS = [
  { code: 'ADMIN', label: '系统管理员' },
  { code: 'APPROVER', label: '带审批的科研人员' },
  { code: 'RESEARCHER', label: '科研人员' }
]

const usersStore = useUsersStore()

const keyword = ref('')
const submitting = ref(false)
const targetUser = ref<UserVO | null>(null)

const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  username: '',
  displayName: '',
  password: '',
  tenantId: '',
  roles: [] as string[]
})
const createRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 64, message: '长度需为 3-64 个字符', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_.-]+$/, message: '只能包含字母、数字与 _ . -', trigger: 'blur' }
  ],
  displayName: [{ required: true, message: '请输入显示名', trigger: 'blur' }],
  password: [{ required: true, min: 6, max: 64, message: '密码至少 6 位', trigger: 'blur' }],
  roles: [{ required: true, type: 'array', min: 1, message: '至少选择一个角色', trigger: 'change' }]
}

const passwordVisible = ref(false)
const passwordFormRef = ref<FormInstance>()
const passwordForm = reactive({ password: '' })
const passwordRules: FormRules = {
  password: [{ required: true, min: 6, max: 64, message: '密码至少 6 位', trigger: 'blur' }]
}

const rolesVisible = ref(false)
const rolesForm = reactive({ roles: [] as string[] })

void usersStore.load()

function roleLabel(code: string): string {
  return ROLE_OPTIONS.find((opt) => opt.code === code)?.label ?? code
}

function roleTagType(code: string): RoleTagType {
  if (code === 'ADMIN') return 'danger'
  if (code === 'APPROVER') return 'warning'
  return 'success'
}

function reload() {
  void usersStore.load(true)
}

function onSearch() {
  void usersStore.search(keyword.value.trim())
}

function onPageChange(page: number) {
  void usersStore.changePage(page)
}

function openCreate() {
  Object.assign(createForm, {
    username: '',
    displayName: '',
    password: '',
    tenantId: '',
    roles: ['RESEARCHER']
  })
  createVisible.value = true
}

async function submitCreate() {
  const valid = await createFormRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await usersStore.create({
      username: createForm.username.trim(),
      password: createForm.password,
      displayName: createForm.displayName.trim(),
      tenantId: createForm.tenantId.trim() || undefined,
      roles: createForm.roles
    })
    ElMessage.success('用户已创建')
    createVisible.value = false
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    submitting.value = false
  }
}

function openPassword(row: UserVO) {
  targetUser.value = row
  passwordForm.password = ''
  passwordVisible.value = true
}

async function submitPassword() {
  const valid = await passwordFormRef.value?.validate().catch(() => false)
  if (!valid || targetUser.value == null) return
  submitting.value = true
  try {
    await usersStore.updatePassword(targetUser.value.id, { password: passwordForm.password })
    ElMessage.success('密码已重置')
    passwordVisible.value = false
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    submitting.value = false
  }
}

function openRoles(row: UserVO) {
  targetUser.value = row
  rolesForm.roles = [...row.roles]
  rolesVisible.value = true
}

async function submitRoles() {
  if (targetUser.value == null) return
  if (rolesForm.roles.length === 0) {
    ElMessage.warning('至少选择一个角色')
    return
  }
  submitting.value = true
  try {
    await usersStore.assignRoles(targetUser.value.id, { roles: rolesForm.roles })
    ElMessage.success('角色已更新')
    rolesVisible.value = false
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    submitting.value = false
  }
}

async function handleToggle(row: UserVO) {
  try {
    await usersStore.toggleStatus(row.id)
    ElMessage.success(row.status === 'ACTIVE' ? '用户已停用' : '用户已启用')
  } catch (error) {
    ElMessage.error(toApiError(error).message)
    await usersStore.load(true)
  }
}
</script>

<style scoped lang="scss">
.users-page {
  max-width: 1180px;
  margin: 0 auto;
}

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 16px;
}

.role-tag {
  margin-right: 6px;
}

.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.dialog-hint {
  margin: 0 0 14px;
  font-size: 12.5px;
  color: var(--rc-text-secondary);
}

.dim {
  color: var(--rc-text-faint);
}
</style>
