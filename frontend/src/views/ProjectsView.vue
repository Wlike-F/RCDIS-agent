<template>
  <div class="projects-page">
    <PageHeader
      kicker="OPERATIONS"
      title="科研项目"
      description="管理在库科研项目的基础信息与项目级预算"
    >
      <template #actions>
        <el-button @click="refresh">
          <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="primary" @click="openCreateProject">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>新增项目
        </el-button>
      </template>
    </PageHeader>

    <div class="rc-card table-card">
      <div class="table-toolbar">
        <el-input v-model="keyword" class="search-input" placeholder="搜索项目编号、名称或负责人" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="statusFilter" class="status-filter" placeholder="全部状态" clearable>
          <el-option v-for="(meta, key) in PROJECT_STATUS" :key="key" :label="meta.label" :value="key" />
        </el-select>
        <span class="table-total num">共 {{ projectsStore.total }} 个项目</span>
      </div>

      <el-skeleton
        v-if="projectsStore.loading && !projectsStore.loaded"
        :rows="6"
        animated
        class="table-skeleton"
      />
      <el-alert
        v-else-if="projectsStore.error"
        type="error"
        :title="projectsStore.error"
        :closable="false"
        show-icon
        class="table-alert"
      >
        <el-button size="small" type="primary" plain @click="refresh">重试</el-button>
      </el-alert>
      <el-table v-else :data="projectsStore.projects" class="project-table" @row-click="openDetail">
        <el-table-column prop="projectCode" label="项目编号" width="170">
          <template #default="{ row }">
            <span class="code-text num">{{ row.projectCode }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="projectName" label="项目名称" min-width="200" show-overflow-tooltip />
        <el-table-column label="负责人" width="110">
          <template #default="{ row }">{{ row.principalInvestigator || '--' }}</template>
        </el-table-column>
        <el-table-column label="总预算" width="140" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.totalBudget) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="已用" width="130" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.usedAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可用" width="130" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.availableAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="projectStatusMeta(row.status).tagType" effect="light">
              {{ projectStatusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行周期" width="210">
          <template #default="{ row }">
            <span class="date-text num">{{ formatDate(row.startDate) }} ~ {{ formatDate(row.endDate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button text type="primary" size="small" @click.stop="openDetail(row)">详情</el-button>
              <el-button text type="primary" size="small" @click.stop="openEditProject(row)">编辑</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyBlock icon="Folder" title="没有匹配的项目" description="调整搜索条件，或先新增科研项目" />
        </template>
      </el-table>
      <el-pagination
        v-if="!projectsStore.error && projectsStore.total > 0"
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        :total="projectsStore.total"
        class="table-pagination"
        layout="total, sizes, prev, pager, next"
        @current-change="handleCurrentChange"
        @size-change="handleSizeChange"
      />
    </div>

    <el-drawer v-model="drawerVisible" :title="detailProject?.projectName || '项目详情'" size="680px">
      <template v-if="detailProject">
        <div class="drawer-section">
          <div class="drawer-section-head">
            <span class="kicker">PROJECT PROFILE</span>
            <div class="drawer-actions">
              <el-button size="small" @click="openEditProject(detailProject)">
                <el-icon style="margin-right: 4px"><Edit /></el-icon>编辑
              </el-button>
              <el-button size="small" type="danger" plain @click="openDeleteProject(detailProject)">
                <el-icon style="margin-right: 4px"><Delete /></el-icon>删除
              </el-button>
            </div>
          </div>
          <el-descriptions :column="2" border class="drawer-desc">
            <el-descriptions-item label="项目编号" :span="2">
              <span class="num">{{ detailProject.projectCode }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="项目负责人">
              {{ detailProject.principalInvestigator || '--' }}
            </el-descriptions-item>
            <el-descriptions-item label="经费来源">
              {{ detailProject.fundingSource || '--' }}
            </el-descriptions-item>
            <el-descriptions-item label="总预算">
              <span class="money-text num">{{ formatMoney(detailProject.totalBudget) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="projectStatusMeta(detailProject.status).tagType" effect="light">
                {{ projectStatusMeta(detailProject.status).label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="已用">
              <span class="money-text num">{{ formatMoney(detailProject.usedAmount) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="冻结">
              <span class="money-text num">{{ formatMoney(detailProject.frozenAmount) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="可用" :span="2">
              <span class="money-text num">{{ formatMoney(detailProject.availableAmount) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="执行周期" :span="2">
              {{ formatDate(detailProject.startDate) }} ~ {{ formatDate(detailProject.endDate) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="projectFormVisible"
      :title="projectDialogTitle"
      width="620px"
      :close-on-click-modal="false"
    >
      <el-form ref="projectFormRef" :model="projectForm" :rules="projectRules" label-position="top">
        <div class="form-grid">
          <el-form-item label="项目编号" prop="projectCode">
            <el-input
              v-model="projectForm.projectCode"
              maxlength="64"
              placeholder="留空自动生成（如 P-2026-001）"
              :disabled="projectFormMode === 'edit'"
            />
            <p v-if="projectFormMode === 'create'" class="form-hint">不填则由系统按年份自动生成流水号</p>
          </el-form-item>
          <el-form-item label="项目名称" prop="projectName">
            <el-input v-model="projectForm.projectName" maxlength="255" placeholder="请输入项目名称" />
          </el-form-item>
          <el-form-item label="负责人" prop="principalInvestigator">
            <el-input v-model="projectForm.principalInvestigator" maxlength="128" placeholder="请输入负责人" />
          </el-form-item>
          <el-form-item label="经费来源" prop="fundingSource">
            <el-input v-model="projectForm.fundingSource" maxlength="128" placeholder="例如 国家自然科学基金" />
          </el-form-item>
          <el-form-item label="总预算（元）" prop="totalBudget">
            <el-input v-model="projectForm.totalBudget" maxlength="18" placeholder="0.00">
              <template #prefix>¥</template>
            </el-input>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="projectForm.status" style="width: 100%" placeholder="请选择状态">
              <el-option v-for="(meta, key) in PROJECT_STATUS" :key="key" :label="meta.label" :value="key" />
            </el-select>
          </el-form-item>
          <el-form-item label="开始日期" prop="startDate">
            <el-date-picker
              v-model="projectForm.startDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
              placeholder="选择开始日期"
            />
          </el-form-item>
          <el-form-item label="结束日期" prop="endDate">
            <el-date-picker
              v-model="projectForm.endDate"
              type="date"
              value-format="YYYY-MM-DD"
              style="width: 100%"
              placeholder="选择结束日期"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="projectFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="projectSubmitting" @click="submitProjectForm">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="projectDeleteVisible"
      title="删除科研项目"
      width="460px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="删除为软删除，会保留删除原因和审计记录。"
        class="delete-alert"
      />
      <el-form ref="projectDeleteFormRef" :model="projectDeleteForm" :rules="deleteRules" label-position="top">
        <el-form-item label="项目" prop="projectName">
          <el-input :model-value="deletingProject?.projectName || ''" disabled />
        </el-form-item>
        <el-form-item label="删除原因" prop="reason">
          <el-input
            v-model="projectDeleteForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请输入删除原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="projectDeleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="projectDeleteSubmitting" @click="submitDeleteProject">
          确认删除
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

import { api } from '@/api'
import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import type { ProjectPageRequest, ProjectVO } from '@/api/types'
import { useProjectsStore } from '@/stores/projects'
import { PROJECT_STATUS, projectStatusMeta } from '@/utils/constants'
import { formatDate, formatMoney } from '@/utils/format'

type ProjectFormMode = 'create' | 'edit'

const projectsStore = useProjectsStore()

const keyword = ref('')
const statusFilter = ref<string | null>(null)
const currentPage = ref(1)
const pageSize = ref(20)
const drawerVisible = ref(false)
const detailProject = ref<ProjectVO | null>(null)
const projectFormVisible = ref(false)
const projectFormMode = ref<ProjectFormMode>('create')
const editingProject = ref<ProjectVO | null>(null)
const projectSubmitting = ref(false)
const projectFormRef = ref<FormInstance>()
const projectDeleteVisible = ref(false)
const projectDeleteSubmitting = ref(false)
const projectDeleteFormRef = ref<FormInstance>()
const deletingProject = ref<ProjectVO | null>(null)

const projectForm = reactive({
  projectCode: '',
  projectName: '',
  principalInvestigator: '',
  fundingSource: '',
  totalBudget: '',
  startDate: '',
  endDate: '',
  status: 'ACTIVE'
})

const projectDeleteForm = reactive({
  reason: ''
})

let searchTimer: ReturnType<typeof window.setTimeout> | undefined

const projectDialogTitle = computed(() => {
  return projectFormMode.value === 'create' ? '新增科研项目' : '编辑科研项目'
})

function buildProjectPageRequest(): ProjectPageRequest {
  const text = keyword.value.trim()
  return {
    current: currentPage.value,
    size: pageSize.value,
    keyword: text || undefined,
    status: statusFilter.value || undefined
  }
}

async function reloadProjects() {
  await projectsStore.load(true, buildProjectPageRequest())
}

function loadProjects() {
  void reloadProjects()
}

function handleCurrentChange() {
  loadProjects()
}

function handleSizeChange() {
  currentPage.value = 1
  loadProjects()
}

function openDetail(row: ProjectVO) {
  detailProject.value = row
  drawerVisible.value = true
}

function refresh() {
  loadProjects()
}

function openCreateProject() {
  projectFormMode.value = 'create'
  editingProject.value = null
  resetProjectForm()
  projectFormVisible.value = true
  void nextTick(() => projectFormRef.value?.clearValidate())
}

function openEditProject(project: ProjectVO) {
  projectFormMode.value = 'edit'
  editingProject.value = project
  fillProjectForm(project)
  projectFormVisible.value = true
  void nextTick(() => projectFormRef.value?.clearValidate())
}

function openDeleteProject(project: ProjectVO) {
  deletingProject.value = project
  projectDeleteForm.reason = ''
  projectDeleteVisible.value = true
  void nextTick(() => projectDeleteFormRef.value?.clearValidate())
}

function resetProjectForm() {
  projectForm.projectCode = ''
  projectForm.projectName = ''
  projectForm.principalInvestigator = ''
  projectForm.fundingSource = ''
  projectForm.totalBudget = ''
  projectForm.startDate = ''
  projectForm.endDate = ''
  projectForm.status = 'ACTIVE'
}

function fillProjectForm(project: ProjectVO) {
  projectForm.projectCode = project.projectCode
  projectForm.projectName = project.projectName
  projectForm.principalInvestigator = project.principalInvestigator || ''
  projectForm.fundingSource = project.fundingSource || ''
  projectForm.totalBudget = String(project.totalBudget)
  projectForm.startDate = project.startDate || ''
  projectForm.endDate = project.endDate || ''
  projectForm.status = project.status
}

function optionalText(value: string): string | undefined {
  const text = value.trim()
  return text ? text : undefined
}

function optionalDate(value: string): string | undefined {
  return value ? value : undefined
}

function validateMoneyText(value: string, emptyMessage: string, callback: (error?: Error) => void) {
  const text = value.trim()
  if (!text) {
    callback(new Error(emptyMessage))
    return
  }
  if (!/^\d+(\.\d{1,2})?$/.test(text)) {
    callback(new Error('金额最多保留 2 位小数'))
    return
  }
  if (Number(text) < 0) {
    callback(new Error('金额不能为负数'))
    return
  }
  callback()
}

function validateProjectMoney(_rule: unknown, value: string, callback: (error?: Error) => void) {
  validateMoneyText(value, '请输入总预算', callback)
}

function validateProjectDates(_rule: unknown, _value: string, callback: (error?: Error) => void) {
  if (projectForm.startDate && projectForm.endDate && projectForm.endDate < projectForm.startDate) {
    callback(new Error('结束日期不能早于开始日期'))
    return
  }
  callback()
}

const projectRules: FormRules = {
  projectCode: [],
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  totalBudget: [{ validator: validateProjectMoney, trigger: 'blur' }],
  startDate: [{ validator: validateProjectDates, trigger: 'change' }],
  endDate: [{ validator: validateProjectDates, trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

const deleteRules: FormRules = {
  reason: [{ required: true, message: '请输入删除原因', trigger: 'blur' }]
}

async function submitProjectForm() {
  const valid = await projectFormRef.value?.validate().catch(() => false)
  if (!valid) return
  projectSubmitting.value = true
  try {
    if (projectFormMode.value === 'create') {
      await createProject()
    } else {
      await updateProject()
    }
    projectFormVisible.value = false
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存科研项目失败')
  } finally {
    projectSubmitting.value = false
  }
}

async function createProject() {
  await api.createProject({
    projectCode: projectForm.projectCode.trim(),
    projectName: projectForm.projectName.trim(),
    principalInvestigator: optionalText(projectForm.principalInvestigator),
    fundingSource: optionalText(projectForm.fundingSource),
    totalBudget: projectForm.totalBudget.trim(),
    startDate: optionalDate(projectForm.startDate),
    endDate: optionalDate(projectForm.endDate),
    status: projectForm.status
  })
  ElMessage.success('科研项目已新增')
  keyword.value = ''
  statusFilter.value = null
  currentPage.value = 1
  await reloadProjects()
}

async function updateProject() {
  if (!editingProject.value) return
  const saved = await api.updateProject(editingProject.value.id, {
    projectName: projectForm.projectName.trim(),
    principalInvestigator: optionalText(projectForm.principalInvestigator),
    fundingSource: optionalText(projectForm.fundingSource),
    totalBudget: projectForm.totalBudget.trim(),
    startDate: optionalDate(projectForm.startDate),
    endDate: optionalDate(projectForm.endDate),
    status: projectForm.status,
    version: editingProject.value.version
  })
  ElMessage.success('科研项目已更新')
  if (detailProject.value?.id === saved.id) {
    detailProject.value = saved
  }
  await reloadProjects()
}

async function submitDeleteProject() {
  const valid = await projectDeleteFormRef.value?.validate().catch(() => false)
  if (!valid || !deletingProject.value) return
  projectDeleteSubmitting.value = true
  try {
    await api.deleteProject(deletingProject.value.id, {
      reason: projectDeleteForm.reason.trim(),
      version: deletingProject.value.version
    })
    ElMessage.success('科研项目已删除')
    if (detailProject.value?.id === deletingProject.value.id) {
      drawerVisible.value = false
      detailProject.value = null
    }
    projectDeleteVisible.value = false
    await reloadProjects()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除科研项目失败')
  } finally {
    projectDeleteSubmitting.value = false
  }
}

watch([keyword, statusFilter], () => {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    currentPage.value = 1
    loadProjects()
  }, 250)
})

onMounted(() => {
  loadProjects()
})

onBeforeUnmount(() => {
  if (searchTimer) window.clearTimeout(searchTimer)
})
</script>

<style scoped lang="scss">
.projects-page {
  width: min(1480px, 100%);
  margin: 0 auto;
}

.table-card {
  padding: 16px 20px 8px;
}

.table-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}

.search-input {
  width: 280px;
}

.status-filter {
  width: 150px;
}

.table-total {
  margin-left: auto;
  font-size: 12px;
  color: var(--rc-text-muted);
}

.table-skeleton {
  padding: 12px 4px 20px;
}

.table-alert {
  margin: 4px 0 16px;
}

.table-pagination {
  justify-content: flex-end;
  padding: 14px 0 2px;
}

:deep(.project-table .el-table__row) {
  cursor: pointer;
}

.row-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.date-text {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.drawer-section {
  margin-bottom: 24px;
}

.drawer-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;

  .kicker {
    display: block;
  }
}

.drawer-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.drawer-desc {
  --el-descriptions-item-bordered-label-background: #f7f9fd;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 14px;
}

.delete-alert {
  margin-bottom: 16px;
}

@media (max-width: 720px) {
  .form-grid {
    grid-template-columns: 1fr;
  }

  .table-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .search-input,
  .status-filter {
    width: 100%;
  }

  .table-total {
    margin-left: 0;
  }

  .drawer-section-head {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
