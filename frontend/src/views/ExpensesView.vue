<template>
  <div class="expenses-page">
    <PageHeader
      kicker="OPERATIONS"
      title="支出管理"
      description="登记、修改与作废支出，登记即占用预算科目余额，数据以 PostgreSQL 为准"
    >
      <template #actions>
        <el-button @click="auditVisible = true">
          <el-icon style="margin-right: 6px"><Document /></el-icon>审计留痕
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>登记支出
        </el-button>
      </template>
    </PageHeader>

    <div v-if="budgetTotals" class="budget-strip rc-card">
      <div class="budget-strip-head">
        <span class="kicker">BUDGET SNAPSHOT</span>
        <span class="budget-project">{{ projectName(projectFilter as number) }}</span>
      </div>
      <el-skeleton v-if="filterCategoriesLoading" :rows="1" animated />
      <div v-else class="budget-strip-body">
        <div class="bs-item">
          <span>科目预算合计</span>
          <b class="num">{{ formatMoney(budgetTotals.allocated) }}</b>
        </div>
        <div class="bs-item">
          <span>已使用</span>
          <b class="num">{{ formatMoney(budgetTotals.used) }}</b>
        </div>
        <div class="bs-item">
          <span>剩余可用</span>
          <b class="num highlight">{{ formatMoney(budgetTotals.available) }}</b>
        </div>
        <div class="bs-item">
          <span>预算科目</span>
          <b class="num">{{ budgetTotals.count }} 个</b>
        </div>
      </div>
    </div>
    <el-alert
      v-if="filterCategoriesError"
      type="error"
      :title="filterCategoriesError"
      :closable="false"
      show-icon
      class="page-note"
    >
      <el-button size="small" type="primary" plain @click="loadFilterCategories(projectFilter)">重试</el-button>
    </el-alert>

    <div class="rc-card table-card">
      <div class="table-toolbar">
        <el-select
          v-model="projectFilter"
          class="filter-project"
          placeholder="全部项目"
          clearable
          :loading="projectsLoading"
        >
          <el-option v-for="project in projects" :key="project.id" :label="project.projectName" :value="project.id" />
        </el-select>
        <el-select
          v-model="categoryFilter"
          class="filter-category"
          placeholder="全部科目"
          clearable
          :disabled="!projectFilter"
        >
          <el-option
            v-for="category in filterCategories"
            :key="category.id"
            :label="category.categoryName"
            :value="category.id"
          />
        </el-select>
        <el-select v-model="statusFilter" class="filter-status" placeholder="全部状态" clearable>
          <el-option v-for="(meta, key) in EXPENSE_STATUS" :key="key" :label="meta.label" :value="key" />
        </el-select>
        <el-input v-model="keyword" class="filter-keyword" placeholder="搜索供应商、事项或发票号" clearable>
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <span class="table-total">共 <b class="num">{{ expensesStore.total }}</b> 条</span>
      </div>

      <el-alert
        v-if="expensesStore.error"
        type="error"
        :title="expensesStore.error"
        :closable="false"
        show-icon
        class="list-error"
      >
        <el-button size="small" type="primary" plain @click="reloadList">重试</el-button>
      </el-alert>

      <el-table v-loading="expensesStore.loading" :data="expensesStore.expenses" :row-class-name="rowClassName">
        <el-table-column label="编号" width="84">
          <template #default="{ row }">
            <span class="code-text num">#{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="所属项目" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.projectName ?? '--' }}</template>
        </el-table-column>
        <el-table-column label="预算科目" width="120">
          <template #default="{ row }">{{ row.categoryName ?? '--' }}</template>
        </el-table-column>
        <el-table-column label="金额" width="130" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.amount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="支出日期" width="112">
          <template #default="{ row }"><span class="num date-text">{{ row.expenseDate }}</span></template>
        </el-table-column>
        <el-table-column label="供应商 / 事项" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :title="row.description">{{ row.vendor || row.description || '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发票 / 支付证明" width="180">
          <template #default="{ row }">
            <div v-if="row.invoiceNo || row.receiptFile" class="proof-cell">
              <el-image
                v-if="row.receiptFile"
                :src="resolveUploadUrl(row.receiptFile)"
                :preview-src-list="[resolveUploadUrl(row.receiptFile)]"
                preview-teleported
                fit="cover"
                class="proof-thumb"
              />
              <span v-if="row.invoiceNo" class="code-text num">{{ row.invoiceNo }}</span>
              <el-tag v-else size="small" type="success" effect="plain">有凭证</el-tag>
            </div>
            <el-tag v-else size="small" type="warning" effect="plain">缺凭证</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag size="small" :type="expenseStatusMeta(row.status).tagType" effect="light">
              {{ expenseStatusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="132" fixed="right">
          <template #default="{ row }">
            <div class="op-cell">
            <el-button
              text
              type="primary"
              size="small"
              :disabled="row.status !== 'REGISTERED'"
              @click="openEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              v-if="row.status === 'REGISTERED'"
              text
              type="danger"
              size="small"
              @click="openVoid(row)"
            >
              作废
            </el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyBlock icon="Money" title="没有匹配的支出记录" description="调整筛选条件，或点击右上角登记一笔支出" />
        </template>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="expensesStore.total"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </div>

    <el-dialog
      v-model="formVisible"
      :title="editing ? '修改支出' : '登记支出'"
      width="560px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="所属项目" prop="projectId">
          <el-select
            v-model="form.projectId"
            style="width: 100%"
            :disabled="editing !== null"
            placeholder="请选择项目"
            @change="handleFormProjectChange"
          >
            <el-option
              v-for="project in projects"
              :key="project.id"
              :label="`${project.projectName}（${project.projectCode}）`"
              :value="project.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="预算科目" prop="budgetCategoryId">
          <el-select
            v-model="form.budgetCategoryId"
            style="width: 100%"
            placeholder="请选择预算科目"
            :loading="formCategoriesLoading"
            :disabled="!form.projectId"
          >
            <el-option
              v-for="category in formCategories"
              :key="category.id"
              :label="`${category.categoryName}（可用 ${formatMoney(category.availableAmount)}）`"
              :value="category.id"
              :disabled="category.status !== 'ACTIVE'"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="金额（元）" prop="amount">
          <el-input-number
            v-model="form.amount"
            :precision="2"
            :min="0.01"
            :step="100"
            :controls="false"
            style="width: 100%"
            placeholder="0.00"
          />
        </el-form-item>
        <el-form-item label="支出日期" prop="expenseDate">
          <el-date-picker
            v-model="form.expenseDate"
            type="date"
            value-format="YYYY-MM-DD"
            style="width: 100%"
            placeholder="选择日期"
          />
        </el-form-item>
        <el-form-item label="供应商" prop="vendor">
          <el-input v-model="form.vendor" placeholder="例如 国药集团化学试剂" maxlength="128" />
        </el-form-item>
        <el-form-item label="用途说明" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" placeholder="简要说明支出用途" />
        </el-form-item>
        <el-form-item label="发票 / 支付证明">
          <div class="proof-field">
            <el-checkbox v-model="form.hasProof">有发票或支付证明</el-checkbox>
            <div v-if="form.hasProof" class="proof-field-body">
              <el-input
                v-model="form.invoiceNo"
                maxlength="128"
                placeholder="发票号（选填，报销材料检查要求尽量填写）"
              />
              <div class="proof-upload-area">
                <div v-if="form.receiptFile" class="proof-preview">
                  <el-image
                    :src="resolveUploadUrl(form.receiptFile)"
                    :preview-src-list="[resolveUploadUrl(form.receiptFile)]"
                    preview-teleported
                    fit="cover"
                    class="proof-thumb proof-thumb-lg"
                  />
                  <el-button size="small" type="danger" plain @click="form.receiptFile = ''">移除图片</el-button>
                </div>
                <el-upload
                  v-else
                  accept="image/jpeg,image/png,image/gif,image/webp,image/bmp"
                  :show-file-list="false"
                  :http-request="handleReceiptUpload"
                >
                  <el-button :loading="uploadingReceipt">
                    <el-icon style="margin-right: 6px"><UploadFilled /></el-icon>上传图片
                  </el-button>
                </el-upload>
                <span class="proof-hint">支持 jpg / png / gif / webp / bmp，单张不超过 10MB</span>
              </div>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="原因 / 备注（选填）">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="2"
            maxlength="500"
            placeholder="将写入审计留痕，例如：支出用途说明、凭证补录说明"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleFormSubmit">
          {{ editing ? '保存并确认' : '保存' }}
        </el-button>
      </template>
    </el-dialog>

    <RiskConfirmDialog
      v-model="riskVisible"
      :operation="riskOperation"
      :target="riskTarget"
      :before="riskBefore"
      :after="riskAfter"
      :loading="applying"
      @confirm="handleRiskConfirm"
    />

    <AuditDrawer v-model="auditVisible" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules, type UploadRequestOptions } from 'element-plus'

import AuditDrawer from '@/components/AuditDrawer.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import RiskConfirmDialog from '@/components/RiskConfirmDialog.vue'
import { api } from '@/api'
import { API_BASE_URL, toApiError } from '@/api/client'
import type { BudgetCategoryVO, ExpenseVO, ProjectVO } from '@/api/types'
import { useExpensesStore } from '@/stores/expenses'
import { EXPENSE_STATUS, expenseStatusMeta } from '@/utils/constants'
import { formatMoney } from '@/utils/format'
import { addMoney, greaterThanMoney, sumMoney } from '@/utils/money'

const expensesStore = useExpensesStore()

// ---------- catalog data ----------
const projects = ref<ProjectVO[]>([])
const projectsLoading = ref(false)

async function loadProjects() {
  projectsLoading.value = true
  try {
    const page = await api.listProjects({ current: 1, size: 200 })
    projects.value = page.records
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    projectsLoading.value = false
  }
}

function projectName(projectId: number): string {
  return projects.value.find((project) => project.id === projectId)?.projectName ?? `项目 #${projectId}`
}

const filterCategories = ref<BudgetCategoryVO[]>([])
const filterCategoriesLoading = ref(false)
const filterCategoriesError = ref<string | null>(null)

async function loadFilterCategories(projectId: number | null) {
  filterCategories.value = []
  filterCategoriesError.value = null
  if (!projectId) return
  filterCategoriesLoading.value = true
  try {
    const page = await api.listBudgetCategories(projectId, { current: 1, size: 500 })
    filterCategories.value = page.records
  } catch (error) {
    filterCategoriesError.value = toApiError(error).message
  } finally {
    filterCategoriesLoading.value = false
  }
}

// ---------- filters & list ----------
const projectFilter = ref<number | null>(null)
const categoryFilter = ref<number | null>(null)
const statusFilter = ref<string | null>(null)
const keyword = ref('')
const keywordTimer = ref<number | null>(null)
const currentPage = ref(1)
const pageSize = ref(10)

const budgetTotals = computed(() => {
  if (!projectFilter.value || filterCategories.value.length === 0) return null
  return {
    allocated: sumMoney(filterCategories.value.map((category) => category.allocatedAmount)),
    used: sumMoney(filterCategories.value.map((category) => category.usedAmount)),
    available: sumMoney(filterCategories.value.map((category) => category.availableAmount)),
    count: filterCategories.value.length
  }
})

function reloadList() {
  void expensesStore.load({
    current: currentPage.value,
    size: pageSize.value,
    projectId: projectFilter.value ?? undefined,
    budgetCategoryId: categoryFilter.value ?? undefined,
    status: statusFilter.value ?? undefined,
    keyword: keyword.value.trim() || undefined
  })
}

watch(projectFilter, () => {
  categoryFilter.value = null
  currentPage.value = 1
  void loadFilterCategories(projectFilter.value)
  reloadList()
})

watch([categoryFilter, statusFilter, currentPage, pageSize], () => {
  reloadList()
})

watch(keyword, () => {
  if (keywordTimer.value !== null) window.clearTimeout(keywordTimer.value)
  keywordTimer.value = window.setTimeout(() => {
    currentPage.value = 1
    reloadList()
  }, 400)
})

onMounted(() => {
  void loadProjects()
  reloadList()
})

function rowClassName({ row }: { row: ExpenseVO }): string {
  return row.status === 'VOIDED' ? 'voided-row' : 'expense-row'
}

// ---------- create / edit form ----------
const formVisible = ref(false)
const editing = ref<ExpenseVO | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  projectId: null as number | null,
  budgetCategoryId: null as number | null,
  amount: null as number | null,
  expenseDate: today(),
  vendor: '',
  description: '',
  invoiceNo: '',
  hasProof: false,
  receiptFile: '',
  reason: ''
})

const formCategories = ref<BudgetCategoryVO[]>([])
const formCategoriesLoading = ref(false)

async function loadFormCategories(projectId: number | null) {
  formCategories.value = []
  if (!projectId) return
  formCategoriesLoading.value = true
  try {
    const page = await api.listBudgetCategories(projectId, { current: 1, size: 500 })
    formCategories.value = page.records
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    formCategoriesLoading.value = false
  }
}

function handleFormProjectChange() {
  form.budgetCategoryId = null
  void loadFormCategories(form.projectId)
}

function categoryAvailableAmount(categoryId: number): string {
  const category = formCategories.value.find((item) => item.id === categoryId)
  return category ? String(category.availableAmount) : '0.00'
}

function validateAmount(_rule: unknown, value: number | null, callback: (error?: Error) => void) {
  if (value === null || value <= 0) {
    callback(new Error('请输入大于 0 的金额'))
    return
  }
  if (!form.budgetCategoryId) {
    callback()
    return
  }
  let available = categoryAvailableAmount(form.budgetCategoryId)
  if (editing.value && editing.value.budgetCategoryId === form.budgetCategoryId) {
    available = addMoney(available, editing.value.amount)
  }
  if (greaterThanMoney(value, available)) {
    callback(new Error(`超过科目可用预算（可用 ${formatMoney(available)}）`))
  } else {
    callback()
  }
}

const rules: FormRules = {
  projectId: [{ required: true, message: '请选择所属项目', trigger: 'change' }],
  budgetCategoryId: [{ required: true, message: '请选择预算科目', trigger: 'change' }],
  amount: [
    { required: true, message: '请输入金额', trigger: 'blur' },
    { validator: validateAmount, trigger: 'blur' }
  ],
  expenseDate: [{ required: true, message: '请选择支出日期', trigger: 'change' }],
  vendor: [{ required: true, message: '请输入供应商', trigger: 'blur' }],
  description: [{ required: true, message: '请输入用途说明', trigger: 'blur' }]
}

function openCreate() {
  editing.value = null
  form.projectId = projectFilter.value
  form.budgetCategoryId = null
  form.amount = null
  form.expenseDate = today()
  form.vendor = ''
  form.description = ''
  form.invoiceNo = ''
  form.hasProof = false
  form.receiptFile = ''
  form.reason = ''
  void loadFormCategories(form.projectId)
  formVisible.value = true
}

function openEdit(row: ExpenseVO) {
  editing.value = row
  form.projectId = row.projectId
  form.budgetCategoryId = row.budgetCategoryId
  form.amount = Number(row.amount)
  form.expenseDate = row.expenseDate
  form.vendor = row.vendor ?? ''
  form.description = row.description
  form.invoiceNo = row.invoiceNo ?? ''
  form.hasProof = Boolean(row.invoiceNo || row.receiptFile)
  form.receiptFile = row.receiptFile ?? ''
  form.reason = ''
  void loadFormCategories(form.projectId)
  formVisible.value = true
}

// ---------- receipt image upload ----------
const uploadingReceipt = ref(false)

function resolveUploadUrl(path: string): string {
  if (!path) return ''
  return path.startsWith('http') ? path : `${API_BASE_URL}${path}`
}

async function handleReceiptUpload(options: UploadRequestOptions) {
  uploadingReceipt.value = true
  try {
    const result = await api.uploadReceiptImage(options.file)
    form.receiptFile = result.url
    ElMessage.success('凭证图片已上传')
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    uploadingReceipt.value = false
  }
}

function categoryName(categoryId: number): string {
  return formCategories.value.find((category) => category.id === categoryId)?.categoryName ?? `科目 #${categoryId}`
}

function describeRecord(
  projectId: number,
  categoryId: number,
  amount: number,
  date: string,
  vendor: string
): string {
  return `${projectName(projectId)} · ${categoryName(categoryId)} · ${formatMoney(
    amount
  )} · ${date} · ${vendor || '未填写事项'}`
}

// ---------- risk confirmation ----------
const riskVisible = ref(false)
const riskOperation = ref('')
const riskTarget = ref('')
const riskBefore = ref('')
const riskAfter = ref('')
const riskMode = ref<'save' | 'void'>('save')
const voidingRow = ref<ExpenseVO | null>(null)
const applying = ref(false)
const saving = ref(false)
const auditVisible = ref(false)

function buildSavePayload(reason: string) {
  return {
    projectId: form.projectId as number,
    budgetCategoryId: form.budgetCategoryId as number,
    amount: form.amount as number,
    expenseDate: form.expenseDate,
    vendor: form.vendor.trim(),
    description: form.description.trim(),
    invoiceNo: form.hasProof ? form.invoiceNo.trim() : '',
    receiptFile: form.hasProof && form.receiptFile ? form.receiptFile : null,
    reason
  }
}

// Creating an expense is the user's explicit intent from the form itself,
// so it submits directly; the confirmation dialog is reserved for update / void.
async function submitCreate() {
  saving.value = true
  try {
    await expensesStore.create(buildSavePayload(form.reason.trim() || '登记支出'))
    ElMessage.success('支出已登记并写入审计留痕')
    formVisible.value = false
    reloadList()
    void loadFilterCategories(projectFilter.value)
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    saving.value = false
  }
}

function handleFormSubmit() {
  void formRef.value
    ?.validate()
    .catch(() => false)
    .then((valid) => {
      if (!valid) return
      if (!editing.value) {
        void submitCreate()
        return
      }
      riskOperation.value = '修改支出'
      riskTarget.value = `支出 #${editing.value.id}`
      riskBefore.value = describeRecord(
        editing.value.projectId,
        editing.value.budgetCategoryId,
        Number(editing.value.amount),
        editing.value.expenseDate,
        editing.value.vendor ?? ''
      )
      riskAfter.value = describeRecord(
        editing.value.projectId,
        form.budgetCategoryId as number,
        form.amount as number,
        form.expenseDate,
        form.vendor
      )
      riskMode.value = 'save'
      riskVisible.value = true
    })
}

function openVoid(row: ExpenseVO) {
  riskMode.value = 'void'
  voidingRow.value = row
  riskOperation.value = '作废支出（软删除）'
  riskTarget.value = `支出 #${row.id}`
  riskBefore.value = describeRecord(
    row.projectId,
    row.budgetCategoryId,
    Number(row.amount),
    row.expenseDate,
    row.vendor ?? ''
  )
  riskAfter.value = '状态变更为已作废，记录保留，预算占用释放'
  riskVisible.value = true
}

async function handleRiskConfirm(reason: string) {
  applying.value = true
  try {
    if (riskMode.value === 'save' && editing.value) {
      await expensesStore.update(editing.value.id, {
        ...buildSavePayload(reason),
        version: editing.value.version
      })
      ElMessage.success('支出已修改并写入审计留痕')
      formVisible.value = false
    } else if (riskMode.value === 'void' && voidingRow.value) {
      await expensesStore.voidExpense(voidingRow.value.id, {
        reason,
        version: voidingRow.value.version
      })
      ElMessage.success('支出已作废并写入审计留痕')
    }
    riskVisible.value = false
    reloadList()
    void loadFilterCategories(projectFilter.value)
  } catch (error) {
    const apiError = toApiError(error)
    ElMessage.error(apiError.message)
    if (apiError.code === 'EXPENSE_VERSION_CONFLICT') {
      reloadList()
    }
  } finally {
    applying.value = false
  }
}

function today(): string {
  const now = new Date()
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}
</script>

<style scoped lang="scss">
.expenses-page {
  max-width: 1280px;
  margin: 0 auto;
}

.page-note {
  margin-bottom: 16px;
}

.budget-strip {
  padding: 16px 20px;
  margin-bottom: 16px;
}

.budget-strip-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.budget-project {
  font-size: 14px;
  font-weight: 600;
  color: var(--rc-text);
}

.budget-strip-body {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--rc-line);
}

.bs-item {
  display: flex;
  flex-direction: column;
  gap: 4px;

  span {
    font-size: 12px;
    color: var(--rc-text-muted);
  }

  b {
    font-size: 17px;
    font-weight: 600;
    color: var(--rc-text);

    &.highlight {
      color: var(--rc-primary-strong);
    }
  }
}

.table-card {
  padding: 16px 20px 12px;
}

.table-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.filter-project {
  width: 200px;
}

.filter-category {
  width: 140px;
}

.filter-status {
  width: 118px;
}

.filter-keyword {
  width: 230px;
}

.table-total {
  margin-left: auto;
  font-size: 12.5px;
  color: var(--rc-text-muted);

  b {
    color: var(--rc-text);
  }
}

.list-error {
  margin-bottom: 12px;
}

:deep(.voided-row) {
  opacity: 0.55;
}

.proof-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.op-cell {
  display: flex;
  align-items: center;
  white-space: nowrap;
}

.proof-thumb {
  width: 46px;
  height: 36px;
  border-radius: 4px;
  border: 1px solid var(--rc-line);
  cursor: zoom-in;
  flex-shrink: 0;
}

.proof-thumb-lg {
  width: 84px;
  height: 64px;
}

.proof-field {
  width: 100%;
}

.proof-field-body {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
  padding: 12px;
  border: 1px dashed var(--rc-line);
  border-radius: 8px;
}

.proof-upload-area {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.proof-preview {
  display: flex;
  align-items: center;
  gap: 10px;
}

.proof-hint {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.date-text {
  font-size: 12px;
  color: var(--rc-text-secondary);
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0 6px;
}
</style>
