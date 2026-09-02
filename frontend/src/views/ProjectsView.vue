<template>
  <div class="projects-page">
    <PageHeader
      kicker="OPERATIONS"
      title="科研项目"
      description="管理在库科研项目的基础信息与预算配置"
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
        <el-table-column label="经费来源" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.fundingSource || '--' }}</template>
        </el-table-column>
        <el-table-column label="总预算" width="150" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.totalBudget) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="剩余预算" width="150" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.remainingBudget) }}</span>
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
            <el-descriptions-item label="总预算" :span="2">
              <span class="money-text num">{{ formatMoney(detailProject.totalBudget) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="剩余预算" :span="2">
              <span class="money-text num">{{ formatMoney(detailProject.remainingBudget) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="projectStatusMeta(detailProject.status).tagType" effect="light">
                {{ projectStatusMeta(detailProject.status).label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="执行周期">
              {{ formatDate(detailProject.startDate) }} ~ {{ formatDate(detailProject.endDate) }}
            </el-descriptions-item>
          </el-descriptions>
        </div>
        <div class="drawer-section">
          <div class="drawer-section-head">
            <span class="kicker">BUDGET DETAIL</span>
            <el-button type="primary" size="small" @click="openCreateBudgetCategory">
              <el-icon style="margin-right: 4px"><Plus /></el-icon>新增科目
            </el-button>
          </div>
          <div v-if="budgetCategories.length > 0" class="budget-summary">
            <span>启用 {{ activeBudgetCategories.length }} 个科目</span>
            <strong class="num">{{ formatMoney(budgetAllocatedTotal) }}</strong>
            <span>剩余未分配</span>
            <strong class="num">{{ formatMoney(budgetUnallocatedAmount) }}</strong>
          </div>
          <el-skeleton v-if="budgetLoading" :rows="4" animated class="budget-skeleton" />
          <el-alert
            v-else-if="budgetError"
            type="error"
            :title="budgetError"
            :closable="false"
            show-icon
          >
            <el-button size="small" type="primary" plain @click="reloadBudgetCategories">重试</el-button>
          </el-alert>
          <el-table v-else-if="budgetCategories.length > 0" :data="budgetCategories" size="small" class="budget-table">
            <el-table-column prop="categoryCode" label="编码" width="110">
              <template #default="{ row }">
                <span class="code-text num">{{ row.categoryCode }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="categoryName" label="科目" min-width="130" show-overflow-tooltip />
            <el-table-column label="分配金额" width="120" align="right">
              <template #default="{ row }">
                <span class="money-text num">{{ formatMoney(row.allocatedAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="可用" width="120" align="right">
              <template #default="{ row }">
                <span class="money-text num">{{ formatMoney(row.availableAmount) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="82">
              <template #default="{ row }">
                <el-tag size="small" :type="budgetCategoryStatusMeta(row.status).tagType" effect="light">
                  {{ budgetCategoryStatusMeta(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="124" fixed="right">
              <template #default="{ row }">
                <div class="row-actions compact">
                  <el-button text type="primary" size="small" @click="openEditBudgetCategory(row)">编辑</el-button>
                  <el-button text type="danger" size="small" @click="openDeleteBudgetCategory(row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
          <EmptyBlock
            v-else
            icon="Coin"
            title="暂无预算科目"
            description="当前项目还没有配置预算科目"
          >
            <el-button type="primary" @click="openCreateBudgetCategory">
              <el-icon style="margin-right: 6px"><Plus /></el-icon>新增科目
            </el-button>
          </EmptyBlock>
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
              placeholder="例如 NSFC-2026-001"
              :disabled="projectFormMode === 'edit'"
            />
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
          <el-form-item v-if="projectFormMode === 'create'" label="快捷模式">
            <div class="quick-mode-row">
              <el-switch v-model="projectForm.quickMode" />
              <span class="quick-mode-tip">自动创建与总预算等额的「默认科目」，无需手动建科目</span>
            </div>
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
        title="删除为软删除，会保留删除原因和审计记录。存在预算科目时后端会拒绝删除。"
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

    <el-dialog
      v-model="budgetFormVisible"
      :title="budgetDialogTitle"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form ref="budgetFormRef" :model="budgetForm" :rules="budgetRules" label-position="top">
        <div class="form-grid">
          <el-form-item label="科目编码" prop="categoryCode">
            <el-input
              v-model="budgetForm.categoryCode"
              maxlength="64"
              placeholder="例如 MATERIAL"
              :disabled="budgetFormMode === 'edit'"
            />
          </el-form-item>
          <el-form-item label="科目名称" prop="categoryName">
            <el-input v-model="budgetForm.categoryName" maxlength="128" placeholder="例如 材料费" />
          </el-form-item>
          <el-form-item label="分配金额（元）" prop="allocatedAmount">
            <el-input v-model="budgetForm.allocatedAmount" maxlength="18" placeholder="0.00">
              <template #prefix>¥</template>
            </el-input>
          </el-form-item>
          <el-form-item label="状态" prop="status">
            <el-select v-model="budgetForm.status" style="width: 100%" placeholder="请选择状态">
              <el-option v-for="(meta, key) in BUDGET_CATEGORY_STATUS" :key="key" :label="meta.label" :value="key" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="备注" prop="remark">
          <el-input
            v-model="budgetForm.remark"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="选填"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="budgetFormVisible = false">取消</el-button>
        <el-button type="primary" :loading="budgetSubmitting" @click="submitBudgetCategory">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="budgetDeleteVisible"
      title="删除预算科目"
      width="460px"
      :close-on-click-modal="false"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="删除为软删除。科目存在已使用或冻结金额时，后端会拒绝删除。"
        class="delete-alert"
      />
      <el-form ref="budgetDeleteFormRef" :model="budgetDeleteForm" :rules="deleteRules" label-position="top">
        <el-form-item label="预算科目" prop="categoryName">
          <el-input :model-value="deletingBudgetCategory?.categoryName || ''" disabled />
        </el-form-item>
        <el-form-item label="删除原因" prop="reason">
          <el-input
            v-model="budgetDeleteForm.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="请输入删除原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="budgetDeleteVisible = false">取消</el-button>
        <el-button type="danger" :loading="budgetDeleteSubmitting" @click="submitDeleteBudgetCategory">
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
import type { BudgetCategoryVO, ProjectPageRequest, ProjectVO } from '@/api/types'
import { useProjectsStore } from '@/stores/projects'
import {
  BUDGET_CATEGORY_STATUS,
  PROJECT_STATUS,
  budgetCategoryStatusMeta,
  projectStatusMeta
} from '@/utils/constants'
import { formatDate, formatMoney } from '@/utils/format'
import { subtractMoney, sumMoney } from '@/utils/money'

type ProjectFormMode = 'create' | 'edit'
type BudgetFormMode = 'create' | 'edit'

const projectsStore = useProjectsStore()

const keyword = ref('')
const statusFilter = ref<string | null>(null)
const currentPage = ref(1)
const pageSize = ref(20)
const drawerVisible = ref(false)
const detailProject = ref<ProjectVO | null>(null)
const budgetCategories = ref<BudgetCategoryVO[]>([])
const budgetLoading = ref(false)
const budgetError = ref<string | null>(null)
const projectFormVisible = ref(false)
const projectFormMode = ref<ProjectFormMode>('create')
const editingProject = ref<ProjectVO | null>(null)
const projectSubmitting = ref(false)
const projectFormRef = ref<FormInstance>()
const projectDeleteVisible = ref(false)
const projectDeleteSubmitting = ref(false)
const projectDeleteFormRef = ref<FormInstance>()
const deletingProject = ref<ProjectVO | null>(null)
const budgetFormVisible = ref(false)
const budgetFormMode = ref<BudgetFormMode>('create')
const editingBudgetCategory = ref<BudgetCategoryVO | null>(null)
const budgetSubmitting = ref(false)
const budgetFormRef = ref<FormInstance>()
const budgetDeleteVisible = ref(false)
const budgetDeleteSubmitting = ref(false)
const budgetDeleteFormRef = ref<FormInstance>()
const deletingBudgetCategory = ref<BudgetCategoryVO | null>(null)

const projectForm = reactive({
  projectCode: '',
  projectName: '',
  principalInvestigator: '',
  fundingSource: '',
  totalBudget: '',
  startDate: '',
  endDate: '',
  status: 'ACTIVE',
  quickMode: true
})

const projectDeleteForm = reactive({
  reason: ''
})

const budgetForm = reactive({
  categoryCode: '',
  categoryName: '',
  allocatedAmount: '',
  status: 'ACTIVE',
  remark: ''
})

const budgetDeleteForm = reactive({
  reason: ''
})

let searchTimer: ReturnType<typeof window.setTimeout> | undefined

const activeBudgetCategories = computed(() => {
  return budgetCategories.value.filter((category) => category.status === 'ACTIVE')
})

const budgetAllocatedTotal = computed(() => {
  return sumMoney(activeBudgetCategories.value.map((category) => category.allocatedAmount))
})

const budgetUnallocatedAmount = computed(() => {
  if (!detailProject.value) return '0.00'
  return subtractMoney(detailProject.value.totalBudget, budgetAllocatedTotal.value)
})

const projectDialogTitle = computed(() => {
  return projectFormMode.value === 'create' ? '新增科研项目' : '编辑科研项目'
})

const budgetDialogTitle = computed(() => {
  return budgetFormMode.value === 'create' ? '新增预算科目' : '编辑预算科目'
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

async function openDetail(row: ProjectVO) {
  detailProject.value = row
  drawerVisible.value = true
  await loadBudgetCategories(row.id)
}

async function loadBudgetCategories(projectId: number) {
  budgetLoading.value = true
  budgetError.value = null
  budgetCategories.value = []
  try {
    const page = await api.listBudgetCategories(projectId, {
      current: 1,
      size: 100
    })
    budgetCategories.value = page.records
  } catch (error) {
    budgetCategories.value = []
    budgetError.value = error instanceof Error ? error.message : '加载预算科目失败'
  } finally {
    budgetLoading.value = false
  }
}

function reloadBudgetCategories() {
  if (!detailProject.value) return
  void loadBudgetCategories(detailProject.value.id)
}

function refresh() {
  loadProjects()
  if (detailProject.value) {
    reloadBudgetCategories()
  }
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
  projectForm.quickMode = true
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

function validateBudgetMoney(_rule: unknown, value: string, callback: (error?: Error) => void) {
  validateMoneyText(value, '请输入分配金额', callback)
}

function validateProjectDates(_rule: unknown, _value: string, callback: (error?: Error) => void) {
  if (projectForm.startDate && projectForm.endDate && projectForm.endDate < projectForm.startDate) {
    callback(new Error('结束日期不能早于开始日期'))
    return
  }
  callback()
}

const projectRules: FormRules = {
  projectCode: [{ required: true, message: '请输入项目编号', trigger: 'blur' }],
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  totalBudget: [{ validator: validateProjectMoney, trigger: 'blur' }],
  startDate: [{ validator: validateProjectDates, trigger: 'change' }],
  endDate: [{ validator: validateProjectDates, trigger: 'change' }],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
}

const budgetRules: FormRules = {
  categoryCode: [{ required: true, message: '请输入科目编码', trigger: 'blur' }],
  categoryName: [{ required: true, message: '请输入科目名称', trigger: 'blur' }],
  allocatedAmount: [{ validator: validateBudgetMoney, trigger: 'blur' }],
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
    status: projectForm.status,
    quickMode: projectForm.quickMode
  })
  ElMessage.success(
    projectForm.quickMode ? '科研项目已新增，已自动创建默认科目' : '科研项目已新增'
  )
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
      budgetCategories.value = []
    }
    projectDeleteVisible.value = false
    await reloadProjects()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除科研项目失败')
  } finally {
    projectDeleteSubmitting.value = false
  }
}

function openCreateBudgetCategory() {
  if (!detailProject.value) return
  budgetFormMode.value = 'create'
  editingBudgetCategory.value = null
  resetBudgetForm()
  budgetFormVisible.value = true
  void nextTick(() => budgetFormRef.value?.clearValidate())
}

function openEditBudgetCategory(category: BudgetCategoryVO) {
  budgetFormMode.value = 'edit'
  editingBudgetCategory.value = category
  fillBudgetForm(category)
  budgetFormVisible.value = true
  void nextTick(() => budgetFormRef.value?.clearValidate())
}

function openDeleteBudgetCategory(category: BudgetCategoryVO) {
  deletingBudgetCategory.value = category
  budgetDeleteForm.reason = ''
  budgetDeleteVisible.value = true
  void nextTick(() => budgetDeleteFormRef.value?.clearValidate())
}

function resetBudgetForm() {
  budgetForm.categoryCode = ''
  budgetForm.categoryName = ''
  budgetForm.allocatedAmount = ''
  budgetForm.status = 'ACTIVE'
  budgetForm.remark = ''
}

function fillBudgetForm(category: BudgetCategoryVO) {
  budgetForm.categoryCode = category.categoryCode
  budgetForm.categoryName = category.categoryName
  budgetForm.allocatedAmount = String(category.allocatedAmount)
  budgetForm.status = category.status
  budgetForm.remark = category.remark || ''
}

async function submitBudgetCategory() {
  const valid = await budgetFormRef.value?.validate().catch(() => false)
  if (!valid || !detailProject.value) return
  budgetSubmitting.value = true
  try {
    if (budgetFormMode.value === 'create') {
      await api.createBudgetCategory(detailProject.value.id, {
        categoryCode: budgetForm.categoryCode.trim(),
        categoryName: budgetForm.categoryName.trim(),
        allocatedAmount: budgetForm.allocatedAmount.trim(),
        status: budgetForm.status,
        remark: optionalText(budgetForm.remark)
      })
      ElMessage.success('预算科目已新增')
    } else if (editingBudgetCategory.value) {
      await api.updateBudgetCategory(detailProject.value.id, editingBudgetCategory.value.id, {
        categoryName: budgetForm.categoryName.trim(),
        allocatedAmount: budgetForm.allocatedAmount.trim(),
        status: budgetForm.status,
        remark: optionalText(budgetForm.remark),
        version: editingBudgetCategory.value.version
      })
      ElMessage.success('预算科目已更新')
    }
    budgetFormVisible.value = false
    await loadBudgetCategories(detailProject.value.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存预算科目失败')
  } finally {
    budgetSubmitting.value = false
  }
}

async function submitDeleteBudgetCategory() {
  const valid = await budgetDeleteFormRef.value?.validate().catch(() => false)
  if (!valid || !detailProject.value || !deletingBudgetCategory.value) return
  budgetDeleteSubmitting.value = true
  try {
    await api.deleteBudgetCategory(detailProject.value.id, deletingBudgetCategory.value.id, {
      reason: budgetDeleteForm.reason.trim(),
      version: deletingBudgetCategory.value.version
    })
    ElMessage.success('预算科目已删除')
    budgetDeleteVisible.value = false
    await loadBudgetCategories(detailProject.value.id)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除预算科目失败')
  } finally {
    budgetDeleteSubmitting.value = false
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

  &.compact {
    gap: 0;
  }
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

.budget-summary {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid var(--rc-line);
  border-radius: 8px;
  color: var(--rc-text-muted);
  font-size: 12px;

  strong {
    color: var(--rc-text);
    font-size: 13px;
  }
}

.budget-skeleton {
  padding: 6px 0;
}

.budget-table {
  width: 100%;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 14px;
}

.delete-alert {
  margin-bottom: 16px;
}

.quick-mode-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.quick-mode-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
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
