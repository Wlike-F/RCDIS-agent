<template>
  <div class="reimbursements-page">
    <PageHeader
      kicker="OPERATIONS"
      title="报销中心"
      description="汇总支出单据生成报销单，材料检查通过后提交审批"
    >
      <template #actions>
        <el-button @click="auditVisible = true">
          <el-icon style="margin-right: 6px"><Document /></el-icon>审计留痕
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>新建报销单
        </el-button>
      </template>
    </PageHeader>

    <div class="rc-card table-card">
      <div class="table-toolbar">
        <el-select
          v-model="projectFilter"
          class="filter-project"
          placeholder="全部项目"
          clearable
          @change="reloadFirstPage"
        >
          <el-option
            v-for="project in projects"
            :key="project.id"
            :label="project.projectName"
            :value="project.id"
          />
        </el-select>
        <el-select
          v-model="statusFilter"
          class="filter-status"
          placeholder="全部状态"
          clearable
          @change="reloadFirstPage"
        >
          <el-option v-for="(meta, key) in REIMBURSEMENT_STATUS" :key="key" :label="meta.label" :value="key" />
        </el-select>
        <span class="table-total num">共 {{ reimbursementsStore.total }} 张报销单</span>
      </div>

      <el-table v-loading="reimbursementsStore.loading" :data="reimbursementsStore.records">
        <el-table-column label="报销单号" width="170">
          <template #default="{ row }"><span class="code-text num">{{ row.reimbursementNo }}</span></template>
        </el-table-column>
        <el-table-column label="项目" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.projectName || row.projectCode || '--' }}</template>
        </el-table-column>
        <el-table-column label="申请人" width="100">
          <template #default="{ row }">{{ row.applicant || '--' }}</template>
        </el-table-column>
        <el-table-column label="单据数" width="86" align="center">
          <template #default="{ row }"><span class="num">{{ row.itemCount }}</span></template>
        </el-table-column>
        <el-table-column label="报销金额" width="140" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.totalAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag size="small" :type="reimbursementStatusMeta(row.status).tagType" effect="light">
              {{ reimbursementStatusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="150">
          <template #default="{ row }">
            <span class="date-text num">{{ row.submittedAt ? formatDateTime(row.submittedAt) : '--' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <div class="op-cell">
              <el-button text type="primary" size="small" @click="openDetail(row)">详情</el-button>
              <el-button
                v-if="row.status === 'DRAFT'"
                text
                type="primary"
                size="small"
                @click="openEdit(row)"
              >
                修改
              </el-button>
              <el-button
                v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
                text
                type="primary"
                size="small"
                @click="openSubmit(row)"
              >
                提交
              </el-button>
              <el-button
                v-if="row.status === 'SUBMITTED'"
                text
                type="success"
                size="small"
                @click="openApprove(row)"
              >
                通过
              </el-button>
              <el-button
                v-if="row.status === 'SUBMITTED'"
                text
                type="danger"
                size="small"
                @click="openReject(row)"
              >
                驳回
              </el-button>
              <el-button
                v-if="row.status === 'DRAFT' || row.status === 'REJECTED'"
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
          <EmptyBlock icon="Tickets" title="没有匹配的报销单" description="调整筛选条件，或点击右上角新建报销单" />
        </template>
      </el-table>

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="reimbursementsStore.total"
          layout="total, sizes, prev, pager, next"
          background
        />
      </div>
    </div>

    <!-- create / edit dialog -->
    <el-dialog
      v-model="createVisible"
      :title="dialogMode === 'edit' ? '修改报销单（草稿）' : '新建报销单'"
      width="760px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top" class="create-form">
        <div class="create-row">
          <el-form-item label="报销项目" required style="flex: 1">
            <el-select
              v-model="createForm.projectId"
              style="width: 100%"
              placeholder="请选择项目"
              :disabled="dialogMode === 'edit'"
              @change="handleProjectChange"
            >
              <el-option
                v-for="project in projects"
                :key="project.id"
                :label="`${project.projectName}（${project.projectCode}）`"
                :value="project.id"
              />
            </el-select>
          </el-form-item>
          <el-form-item label="申请人" required style="width: 200px">
            <el-input v-model="createForm.applicant" maxlength="32" />
          </el-form-item>
        </div>

        <el-form-item label="选择支出单据" required>
          <el-table
            ref="pickTableRef"
            v-loading="availableLoading"
            :data="availableExpenses"
            class="pick-table"
            max-height="280"
            empty-text="该项目暂无可关联的已登记支出"
            @selection-change="handleSelectionChange"
          >
            <el-table-column type="selection" width="42" />
            <el-table-column label="编号" width="70">
              <template #default="{ row }"><span class="code-text num">#{{ row.id }}</span></template>
            </el-table-column>
            <el-table-column label="预算科目" width="120" show-overflow-tooltip>
              <template #default="{ row }">{{ row.categoryName || '--' }}</template>
            </el-table-column>
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }"><span class="num">{{ formatMoney(row.amount) }}</span></template>
            </el-table-column>
            <el-table-column label="支出日期" width="108">
              <template #default="{ row }"><span class="num date-text">{{ row.expenseDate }}</span></template>
            </el-table-column>
            <el-table-column label="供应商 / 事项" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.vendor || row.description || '--' }}</template>
            </el-table-column>
            <el-table-column label="发票 / 凭证" width="150">
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
                <el-tag v-else size="small" type="warning" effect="plain">缺材料</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <p class="field-tip">
            仅显示该项目下未关联报销单的已登记支出；材料检查要求发票号或发票/支付证明图片至少其一，供应商、用途说明齐全
          </p>
        </el-form-item>

        <el-form-item v-if="dialogMode === 'create'" label="快捷录入支出（可选）">
          <div class="quick-entry">
            <div class="quick-form">
              <div class="quick-row">
                <el-select
                  v-model="quickForm.budgetCategoryId"
                  class="quick-category"
                  placeholder="预算科目"
                  :loading="categoriesLoading"
                >
                  <el-option
                    v-for="category in budgetCategories"
                    :key="category.id"
                    :label="`${category.categoryName}（可用 ${formatMoney(category.availableAmount)}）`"
                    :value="category.id"
                  />
                </el-select>
                <el-date-picker
                  v-model="quickForm.expenseDate"
                  class="quick-date"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="支出日期"
                />
                <el-input v-model="quickForm.amount" class="quick-amount" placeholder="金额">
                  <template #prefix>¥</template>
                </el-input>
                <el-input v-model="quickForm.vendor" class="quick-vendor" maxlength="128" placeholder="供应商（选填）" />
              </div>
              <div class="quick-row">
                <el-input v-model="quickForm.description" class="quick-desc" maxlength="500" placeholder="用途说明" />
                <el-input v-model="quickForm.invoiceNo" class="quick-invoice" maxlength="128" placeholder="发票号（选填）" />
                <div class="quick-upload">
                  <template v-if="quickForm.receiptFile">
                    <el-image
                      :src="resolveUploadUrl(quickForm.receiptFile)"
                      :preview-src-list="[resolveUploadUrl(quickForm.receiptFile)]"
                      preview-teleported
                      fit="cover"
                      class="proof-thumb"
                    />
                    <el-button size="small" type="danger" plain @click="quickForm.receiptFile = ''">移除</el-button>
                  </template>
                  <el-upload
                    v-else
                    accept="image/jpeg,image/png,image/gif,image/webp,image/bmp"
                    :show-file-list="false"
                    :http-request="handleQuickReceiptUpload"
                  >
                    <el-button size="small" :loading="quickUploading">上传凭证</el-button>
                  </el-upload>
                </div>
                <el-button type="primary" plain @click="addQuickExpense">添加</el-button>
              </div>
            </div>
            <el-table v-if="quickEntries.length > 0" :data="quickEntries" size="small" class="quick-list">
              <el-table-column label="科目" width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ quickCategoryName(row.budgetCategoryId) }}</template>
              </el-table-column>
              <el-table-column label="日期" width="100">
                <template #default="{ row }"><span class="num date-text">{{ row.expenseDate }}</span></template>
              </el-table-column>
              <el-table-column label="金额" width="100" align="right">
                <template #default="{ row }"><span class="num">{{ formatMoney(row.amount) }}</span></template>
              </el-table-column>
              <el-table-column label="供应商 / 用途" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">{{ row.vendor || row.description }}</template>
              </el-table-column>
              <el-table-column label="发票 / 凭证" width="110">
                <template #default="{ row }">
                  <el-tag v-if="row.invoiceNo || row.receiptFile" size="small" type="success" effect="plain">齐全</el-tag>
                  <el-tag v-else size="small" type="warning" effect="plain">缺材料</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70">
                <template #default="{ $index }">
                  <el-button text type="danger" size="small" @click="removeQuickEntry($index)">移除</el-button>
                </template>
              </el-table-column>
            </el-table>
            <p class="field-tip">
              提交时先自动登记为新支出（占用预算、写入审计），再与上方勾选单据一起挂入报销单
            </p>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <template v-if="dialogMode === 'edit'">
          <el-button type="primary" :loading="creating" @click="saveEdit">保存修改</el-button>
        </template>
        <template v-else>
          <el-button :loading="creating" @click="submitCreate(false)">仅存草稿</el-button>
          <el-button type="primary" :loading="creating" @click="submitCreate(true)">创建并提交</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- detail drawer -->
    <el-drawer v-model="detailVisible" :title="detailData?.order.reimbursementNo || '报销单详情'" size="620px">
      <template v-if="detailData">
        <div class="drawer-section">
          <span class="kicker">REIMBURSEMENT PROFILE</span>
          <el-descriptions :column="2" border class="drawer-desc">
            <el-descriptions-item label="报销单号" :span="2">
              <span class="num">{{ detailData.order.reimbursementNo }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="项目" :span="2">
              {{ detailData.order.projectName || detailData.order.projectCode || '--' }}
            </el-descriptions-item>
            <el-descriptions-item label="申请人">{{ detailData.order.applicant || '--' }}</el-descriptions-item>
            <el-descriptions-item label="审批人">{{ detailData.order.principalInvestigator || '未指定' }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="reimbursementStatusMeta(detailData.order.status).tagType" effect="light">
                {{ reimbursementStatusMeta(detailData.order.status).label }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="提交时间">
              <span class="num">{{ detailData.order.submittedAt ? formatDateTime(detailData.order.submittedAt) : '--' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="审批时间">
              <span class="num">{{ detailData.order.approvedAt ? formatDateTime(detailData.order.approvedAt) : '--' }}</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detailData.order.rejectReason" label="驳回原因" :span="2">
              <span class="reject-reason">{{ detailData.order.rejectReason }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="drawer-section">
          <span class="kicker">LINKED EXPENSES</span>
          <el-table :data="detailData.items" size="small" class="drawer-table">
            <el-table-column label="编号" width="70">
              <template #default="{ row }"><span class="code-text num">#{{ row.expenseId }}</span></template>
            </el-table-column>
            <el-table-column label="科目" width="110" show-overflow-tooltip>
              <template #default="{ row }">{{ row.categoryName || '--' }}</template>
            </el-table-column>
            <el-table-column label="金额" width="105" align="right">
              <template #default="{ row }"><span class="num">{{ formatMoney(row.amount) }}</span></template>
            </el-table-column>
            <el-table-column label="日期" width="100">
              <template #default="{ row }"><span class="num date-text">{{ row.expenseDate || '--' }}</span></template>
            </el-table-column>
            <el-table-column label="发票 / 凭证" width="130">
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
                <el-tag v-else size="small" type="warning" effect="plain">缺</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态">
              <template #default="{ row }">
                <el-tag size="small" :type="expenseStatusMeta(row.expenseStatus).tagType" effect="light">
                  {{ expenseStatusMeta(row.expenseStatus).label }}
                </el-tag>
              </template>
            </el-table-column>
            <template #empty><span class="date-text">无关联单据</span></template>
          </el-table>
          <p class="drawer-total">
            合计 <b class="num">{{ formatMoney(detailData.order.totalAmount) }}</b>
          </p>
        </div>

        <div class="drawer-section">
          <span class="kicker">MATERIAL CHECK</span>
          <div class="check-panel">
            <div class="check-head">
              <el-button size="small" :loading="checking" @click="runCheck(detailData.order.id)">
                <el-icon style="margin-right: 4px"><Refresh /></el-icon>执行材料检查
              </el-button>
              <span v-if="checkResult" class="check-summary" :class="checkResult.pass ? 'pass' : 'fail'">
                {{
                  checkResult.pass
                    ? `材料齐全，共检查 ${checkResult.checkedCount} 张单据`
                    : `发现 ${checkResult.findings.length} 项待处理`
                }}
              </span>
            </div>
            <ul v-if="checkResult && checkResult.findings.length > 0" class="check-list">
              <li
                v-for="(finding, index) in checkResult.findings"
                :key="index"
                class="check-item"
                :class="finding.level"
              >
                <el-icon class="check-icon" :size="14">
                  <CircleClose v-if="finding.level === 'missing'" />
                  <WarningFilled v-else />
                </el-icon>
                <span class="check-label">{{ finding.label }}</span>
                <span class="check-message">{{ finding.message }}</span>
              </li>
            </ul>
            <el-alert
              v-if="checkResult && checkResult.pass"
              type="success"
              title="材料检查通过，可以提交审批"
              :closable="false"
              show-icon
            />
            <p v-if="checkResult && !checkResult.pass" class="field-tip">
              可在「支出管理」页面编辑对应单据补齐材料后重新检查；标记为提醒的项目不阻断提交
            </p>
          </div>
        </div>

        <div class="drawer-actions">
          <el-button
            v-if="detailData.order.status === 'DRAFT'"
            @click="openEdit(detailData.order)"
          >
            修改草稿
          </el-button>
          <el-button
            v-if="detailData.order.status === 'DRAFT' || detailData.order.status === 'REJECTED'"
            type="primary"
            @click="openSubmit(detailData.order)"
          >
            提交审批
          </el-button>
          <el-button
            v-if="detailData.order.status === 'SUBMITTED'"
            type="success"
            @click="openApprove(detailData.order)"
          >
            审批通过
          </el-button>
          <el-button
            v-if="detailData.order.status === 'SUBMITTED'"
            type="danger"
            plain
            @click="openReject(detailData.order)"
          >
            审批驳回
          </el-button>
          <el-button
            v-if="detailData.order.status === 'DRAFT' || detailData.order.status === 'REJECTED'"
            type="danger"
            plain
            @click="openVoid(detailData.order)"
          >
            作废
          </el-button>
        </div>
      </template>
    </el-drawer>

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
import { nextTick, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type TableInstance, type UploadRequestOptions } from 'element-plus'

import AuditDrawer from '@/components/AuditDrawer.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import RiskConfirmDialog from '@/components/RiskConfirmDialog.vue'
import { api } from '@/api'
import { API_BASE_URL, toApiError } from '@/api/client'
import { REQUEST_CONTEXT } from '@/api/context'
import type {
  BudgetCategoryVO,
  ExpenseVO,
  MaterialCheckVO,
  ProjectVO,
  QuickExpenseInput,
  ReimbursementDetailVO,
  ReimbursementVO
} from '@/api/types'
import { useReimbursementsStore } from '@/stores/reimbursements'
import {
  REIMBURSEMENT_STATUS,
  expenseStatusMeta,
  reimbursementStatusMeta
} from '@/utils/constants'
import { formatDateTime, formatMoney } from '@/utils/format'

const reimbursementsStore = useReimbursementsStore()

// ---------- list ----------
const projects = ref<ProjectVO[]>([])
const statusFilter = ref<string | null>(null)
const projectFilter = ref<number | null>(null)
const currentPage = ref(1)
const pageSize = ref(10)
const auditVisible = ref(false)

async function loadProjects() {
  try {
    const page = await api.listProjects({ current: 1, size: 100 })
    projects.value = page.records
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  }
}

function loadList() {
  reimbursementsStore.load({
    current: currentPage.value,
    size: pageSize.value,
    status: statusFilter.value ?? undefined,
    projectId: projectFilter.value ?? undefined
  })
}

function reloadFirstPage() {
  currentPage.value = 1
  loadList()
}

watch([currentPage, pageSize], () => loadList())

onMounted(() => {
  loadProjects()
  loadList()
})

// ---------- create / edit ----------
const createVisible = ref(false)
const creating = ref(false)
const availableLoading = ref(false)
const availableExpenses = ref<ExpenseVO[]>([])
const selectedExpenses = ref<ExpenseVO[]>([])
const pickTableRef = ref<TableInstance>()
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const editingVersion = ref(0)
const createForm = reactive({
  projectId: null as number | null,
  applicant: REQUEST_CONTEXT.userName
})

async function loadAvailableExpenses(projectId: number, excludeOrderId?: number) {
  availableLoading.value = true
  try {
    availableExpenses.value = await reimbursementsStore.listAvailableExpenses(projectId, excludeOrderId)
  } catch (error) {
    availableExpenses.value = []
    ElMessage.error(toApiError(error).message)
  } finally {
    availableLoading.value = false
  }
}

function handleProjectChange(projectId: number | null) {
  selectedExpenses.value = []
  pickTableRef.value?.clearSelection()
  quickEntries.value = []
  resetQuickForm()
  if (projectId === null) {
    availableExpenses.value = []
    budgetCategories.value = []
    return
  }
  void loadAvailableExpenses(projectId)
  void loadBudgetCategories(projectId)
}

function handleSelectionChange(rows: ExpenseVO[]) {
  selectedExpenses.value = rows
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  editingVersion.value = 0
  createForm.projectId = null
  createForm.applicant = REQUEST_CONTEXT.userName
  selectedExpenses.value = []
  availableExpenses.value = []
  budgetCategories.value = []
  quickEntries.value = []
  resetQuickForm()
  createVisible.value = true
}

async function openEdit(row: ReimbursementVO) {
  dialogMode.value = 'edit'
  editingId.value = row.id
  createVisible.value = true
  try {
    const detail = await reimbursementsStore.fetchDetail(row.id)
    editingVersion.value = detail.order.version
    createForm.projectId = detail.order.projectId
    createForm.applicant = detail.order.applicant || REQUEST_CONTEXT.userName
    quickEntries.value = []
    resetQuickForm()
    // excludeOrderId lets the order's own linked expenses show up as selectable rows
    await loadAvailableExpenses(detail.order.projectId, row.id)
    const linkedIds = new Set(detail.items.map((item) => item.expenseId))
    await nextTick()
    pickTableRef.value?.clearSelection()
    availableExpenses.value.forEach((expense) => {
      if (linkedIds.has(expense.id)) {
        pickTableRef.value?.toggleRowSelection(expense, true)
      }
    })
  } catch (error) {
    ElMessage.error(toApiError(error).message)
    createVisible.value = false
  }
}

async function saveEdit() {
  if (editingId.value === null) return
  if (!createForm.applicant.trim()) {
    ElMessage.warning('请填写申请人')
    return
  }
  if (selectedExpenses.value.length === 0) {
    ElMessage.warning('请至少选择一张支出单据')
    return
  }
  creating.value = true
  try {
    await reimbursementsStore.update(editingId.value, {
      applicant: createForm.applicant.trim(),
      expenseIds: selectedExpenses.value.map((item) => item.id),
      reason: '修改报销单草稿',
      version: editingVersion.value
    })
    ElMessage.success('报销单草稿已更新')
    createVisible.value = false
    loadList()
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    creating.value = false
  }
}

// ---------- quick expense entry ----------
const budgetCategories = ref<BudgetCategoryVO[]>([])
const categoriesLoading = ref(false)
const quickUploading = ref(false)
const quickEntries = ref<QuickExpenseInput[]>([])
const quickForm = reactive({
  budgetCategoryId: null as number | null,
  expenseDate: '',
  amount: '',
  vendor: '',
  description: '',
  invoiceNo: '',
  receiptFile: ''
})

function resetQuickForm() {
  quickForm.budgetCategoryId = null
  quickForm.expenseDate = ''
  quickForm.amount = ''
  quickForm.vendor = ''
  quickForm.description = ''
  quickForm.invoiceNo = ''
  quickForm.receiptFile = ''
}

async function loadBudgetCategories(projectId: number) {
  categoriesLoading.value = true
  try {
    const page = await api.listBudgetCategories(projectId, { current: 1, size: 100 })
    budgetCategories.value = page.records.filter((category) => category.status === 'ACTIVE')
  } catch (error) {
    budgetCategories.value = []
    ElMessage.error(toApiError(error).message)
  } finally {
    categoriesLoading.value = false
  }
}

function quickCategoryName(categoryId: number): string {
  return budgetCategories.value.find((category) => category.id === categoryId)?.categoryName || `#${categoryId}`
}

async function handleQuickReceiptUpload(options: UploadRequestOptions) {
  quickUploading.value = true
  try {
    const result = await api.uploadReceiptImage(options.file)
    quickForm.receiptFile = result.url
    ElMessage.success('凭证图片已上传')
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    quickUploading.value = false
  }
}

function addQuickExpense() {
  if (quickForm.budgetCategoryId === null) {
    ElMessage.warning('请选择预算科目')
    return
  }
  if (!quickForm.expenseDate) {
    ElMessage.warning('请选择支出日期')
    return
  }
  const amount = Number(quickForm.amount)
  if (!Number.isFinite(amount) || amount <= 0) {
    ElMessage.warning('请输入大于 0 的金额')
    return
  }
  if (!quickForm.description.trim()) {
    ElMessage.warning('请填写用途说明')
    return
  }
  quickEntries.value.push({
    budgetCategoryId: quickForm.budgetCategoryId,
    amount: amount.toFixed(2),
    expenseDate: quickForm.expenseDate,
    description: quickForm.description.trim(),
    vendor: quickForm.vendor.trim() || undefined,
    invoiceNo: quickForm.invoiceNo.trim() || undefined,
    receiptFile: quickForm.receiptFile || undefined
  })
  resetQuickForm()
  ElMessage.success('已加入待提交列表，提交时自动登记为支出')
}

function removeQuickEntry(index: number) {
  quickEntries.value.splice(index, 1)
}

async function submitCreate(submitNow: boolean) {
  if (!createForm.projectId) {
    ElMessage.warning('请选择报销项目')
    return
  }
  if (!createForm.applicant.trim()) {
    ElMessage.warning('请填写申请人')
    return
  }
  if (selectedExpenses.value.length === 0 && quickEntries.value.length === 0) {
    ElMessage.warning('请至少选择一张支出单据或快捷录入一笔支出')
    return
  }
  creating.value = true
  try {
    const detail = await reimbursementsStore.create({
      projectId: createForm.projectId,
      applicant: createForm.applicant.trim(),
      expenseIds: selectedExpenses.value.map((item) => item.id),
      newExpenses: quickEntries.value.length > 0 ? quickEntries.value : undefined,
      reason: submitNow ? '创建并提交' : '新建报销单',
      submitNow
    })
    if (submitNow && detail.order.status === 'SUBMITTED') {
      ElMessage.success('报销单已创建并提交，已推送飞书通知')
    } else if (submitNow) {
      ElMessage.warning('材料检查未通过，报销单已保存为草稿，请补齐材料后再提交')
    } else {
      ElMessage.success('报销单已创建并写入审计留痕')
    }
    createVisible.value = false
    reloadFirstPage()
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    creating.value = false
  }
}

// ---------- risk confirmation ----------
const riskVisible = ref(false)
const riskOperation = ref('')
const riskTarget = ref('')
const riskBefore = ref('')
const riskAfter = ref('')
const riskMode = ref<'submit' | 'approve' | 'reject' | 'void'>('submit')
const pendingId = ref<number | null>(null)
const applying = ref(false)

function openSubmit(row: ReimbursementVO) {
  riskMode.value = 'submit'
  pendingId.value = row.id
  riskOperation.value = '提交报销单'
  riskTarget.value = `${row.reimbursementNo} · ${row.projectName || row.projectCode || ''}`
  riskBefore.value = `状态：${reimbursementStatusMeta(row.status).label}`
  riskAfter.value = '状态变更为待审批，进入审批流程；材料检查不通过时提交会被拒绝'
  riskVisible.value = true
}

function openApprove(row: ReimbursementVO) {
  riskMode.value = 'approve'
  pendingId.value = row.id
  riskOperation.value = '审批通过'
  riskTarget.value = `${row.reimbursementNo} · 合计 ${formatMoney(row.totalAmount)}`
  riskBefore.value = '状态：待审批'
  riskAfter.value = '状态变更为已通过，关联支出置为已报销（预算占用发生在支出登记时，审批通过不再变动）'
  riskVisible.value = true
}

function openReject(row: ReimbursementVO) {
  riskMode.value = 'reject'
  pendingId.value = row.id
  riskOperation.value = '审批驳回'
  riskTarget.value = `${row.reimbursementNo} · 合计 ${formatMoney(row.totalAmount)}`
  riskBefore.value = '状态：待审批'
  riskAfter.value = '状态变更为已驳回，可补齐材料后重新提交'
  riskVisible.value = true
}

function openVoid(row: ReimbursementVO) {
  riskMode.value = 'void'
  pendingId.value = row.id
  riskOperation.value = '作废报销单'
  riskTarget.value = `${row.reimbursementNo} · 合计 ${formatMoney(row.totalAmount)}`
  riskBefore.value = `状态：${reimbursementStatusMeta(row.status).label}`
  riskAfter.value = '状态变更为已作废（不可恢复），关联支出将被释放，可重新挂入其他报销单'
  riskVisible.value = true
}

async function handleRiskConfirm(reason: string) {
  if (pendingId.value === null) return
  applying.value = true
  try {
    if (riskMode.value === 'submit') {
      await reimbursementsStore.submit(pendingId.value, { reason })
      ElMessage.success('报销单已提交审批')
    } else if (riskMode.value === 'approve') {
      await reimbursementsStore.approve(pendingId.value, { reason })
      ElMessage.success('报销单已审批通过，关联支出已置为已报销')
    } else if (riskMode.value === 'reject') {
      await reimbursementsStore.reject(pendingId.value, { reason })
      ElMessage.success('报销单已驳回，可补齐材料后重新提交')
    } else {
      await reimbursementsStore.voidOrder(pendingId.value, { reason })
      ElMessage.success('报销单已作废，关联支出已释放')
    }
    riskVisible.value = false
    loadList()
    if (detailVisible.value && detailData.value) {
      await refreshDetail(detailData.value.order.id)
    }
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    applying.value = false
  }
}

// ---------- detail drawer ----------
const detailVisible = ref(false)
const detailData = ref<ReimbursementDetailVO | null>(null)
const checkResult = ref<MaterialCheckVO | null>(null)
const checking = ref(false)

async function refreshDetail(id: number) {
  try {
    detailData.value = await reimbursementsStore.fetchDetail(id)
    checkResult.value = await reimbursementsStore.checkMaterials(id)
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  }
}

async function openDetail(row: ReimbursementVO) {
  detailData.value = null
  checkResult.value = null
  detailVisible.value = true
  await refreshDetail(row.id)
}

async function runCheck(id: number) {
  checking.value = true
  try {
    checkResult.value = await reimbursementsStore.checkMaterials(id)
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    checking.value = false
  }
}

// ---------- helpers ----------
function resolveUploadUrl(path: string): string {
  if (!path) return ''
  return path.startsWith('http') ? path : `${API_BASE_URL}${path}`
}
</script>

<style scoped lang="scss">
.reimbursements-page {
  max-width: 1280px;
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

.filter-project {
  width: 220px;
}

.filter-status {
  width: 150px;
}

.table-total {
  margin-left: auto;
  font-size: 12px;
  color: var(--rc-text-muted);
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0 8px;
}

.op-cell {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
}

.date-text {
  font-size: 12px;
  color: var(--rc-text-secondary);
}

.proof-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.proof-thumb {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  border: 1px solid var(--rc-line);
  flex-shrink: 0;
  cursor: zoom-in;
}

.create-form {
  .create-row {
    display: flex;
    gap: 16px;
  }

  .pick-table {
    width: 100%;
  }
}

.quick-entry {
  width: 100%;
}

.quick-form {
  width: 100%;
  padding: 12px;
  border: 1px dashed var(--rc-line);
  border-radius: 10px;
  background: #fafbfe;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.quick-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.quick-category {
  width: 220px;
}

.quick-date {
  width: 140px !important;
}

.quick-amount {
  width: 110px;
}

.quick-vendor {
  flex: 1;
  min-width: 140px;
}

.quick-desc {
  flex: 1;
  min-width: 160px;
}

.quick-invoice {
  width: 150px;
}

.quick-upload {
  display: flex;
  align-items: center;
  gap: 8px;
}

.quick-list {
  width: 100%;
  margin-top: 10px;
}

.drawer-section {
  margin-bottom: 24px;

  .kicker {
    display: block;
    margin-bottom: 10px;
  }
}

.drawer-desc {
  --el-descriptions-item-bordered-label-background: #f7f9fd;
}

.reject-reason {
  color: var(--rc-danger);
}

.drawer-table {
  width: 100%;
}

.drawer-total {
  margin: 10px 0 0;
  text-align: right;
  font-size: 13px;
  color: var(--rc-text-secondary);

  b {
    font-size: 15px;
    color: var(--rc-text);
  }
}

.check-panel {
  border: 1px solid var(--rc-line);
  border-radius: 10px;
  padding: 14px;
  background: #fafbfe;
}

.check-head {
  display: flex;
  align-items: center;
  gap: 12px;
}

.check-summary {
  font-size: 12.5px;
  font-weight: 600;

  &.pass {
    color: var(--rc-success);
  }

  &.fail {
    color: var(--rc-danger);
  }
}

.check-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.check-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;

  &.missing .check-icon,
  &.missing .check-message {
    color: var(--rc-danger);
  }

  &.warning .check-icon,
  &.warning .check-message {
    color: var(--rc-warning, #e6a23c);
  }
}

.check-icon {
  flex-shrink: 0;
}

.check-label {
  color: var(--rc-text-secondary);
  flex-shrink: 0;
}

.drawer-actions {
  display: flex;
  gap: 10px;
  padding-top: 4px;
  border-top: 1px solid var(--rc-line);
}
</style>
