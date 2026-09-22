<template>
  <div class="reimbursements-page">
    <PageHeader
      kicker="OPERATIONS"
      title="报销中心"
      description="新建报销单并填写明细；报销支付走审批，公卡支付提交即入账"
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
        <el-table-column label="报销单号" width="150">
          <template #default="{ row }"><span class="code-text num">{{ row.reimbursementNo }}</span></template>
        </el-table-column>
        <el-table-column label="项目" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.projectName || row.projectCode || '--' }}</template>
        </el-table-column>
        <el-table-column label="申请人" width="80">
          <template #default="{ row }">{{ row.applicant || '--' }}</template>
        </el-table-column>
        <el-table-column label="支付方式" width="92">
          <template #default="{ row }">
            <el-tag size="small" :type="paymentTypeMeta(row.paymentType).tagType" effect="plain">
              {{ paymentTypeMeta(row.paymentType).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="发票/凭证" min-width="190">
          <template #default="{ row }">
            <div v-if="row.receiptFiles && row.receiptFiles.length" class="proof-cell">
              <AuthenticatedImage
                v-for="file in row.receiptFiles.slice(0, 3)"
                :key="file"
                :src="file"
                :preview="true"
                fit="cover"
                class="proof-thumb"
              />
              <span v-if="row.receiptFiles.length > 3" class="num proof-more">
                +{{ row.receiptFiles.length - 3 }}
              </span>
            </div>
            <span v-else class="code-text">{{ row.invoiceSummary || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="明细数" width="64" align="center">
          <template #default="{ row }"><span class="num">{{ row.itemCount }}</span></template>
        </el-table-column>
        <el-table-column label="报销金额" width="110" align="right">
          <template #default="{ row }">
            <span class="money-text num">{{ formatMoney(row.totalAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="82">
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
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <div class="op-cell">
              <el-button text type="primary" size="small" @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.status === 'draft'" text type="primary" size="small" @click="openEdit(row)">
                修改
              </el-button>
              <el-button
                v-if="row.paymentType === 'reimbursement' && (row.status === 'draft' || row.status === 'rejected')"
                text
                type="primary"
                size="small"
                @click="openSubmit(row)"
              >
                提交
              </el-button>
              <el-button
                v-if="row.status === 'submitted'"
                text
                type="warning"
                size="small"
                @click="openWithdraw(row)"
              >
                撤回
              </el-button>
              <el-button
                v-if="row.status === 'submitted'"
                text
                type="success"
                size="small"
                @click="openApprove(row)"
              >
                通过
              </el-button>
              <el-button
                v-if="row.status === 'submitted'"
                text
                type="danger"
                size="small"
                @click="openReject(row)"
              >
                驳回
              </el-button>
              <el-button
                v-if="canVoid(row)"
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
      width="860px"
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

        <el-form-item label="支付方式" required>
          <el-select v-model="createForm.paymentType" :disabled="dialogMode === 'edit'" style="width: 280px">
            <el-option label="报销支付（走审批）" value="reimbursement" />
            <el-option label="公卡支付（提交即入账，免审批）" value="public_payment" />
          </el-select>
        </el-form-item>

        <el-form-item label="报销明细" required>
          <div class="items-editor">
            <div v-for="(row, index) in itemRows" :key="index" class="item-row">
              <el-input v-model="row.amount" class="item-amount" placeholder="金额">
                <template #prefix>¥</template>
              </el-input>
              <el-date-picker
                v-model="row.expenseDate"
                type="date"
                value-format="YYYY-MM-DD"
                class="item-date"
                placeholder="日期"
              />
              <el-input
                v-if="createForm.paymentType === 'reimbursement'"
                v-model="row.vendor"
                class="item-vendor"
                maxlength="128"
                placeholder="供应商"
              />
              <el-input
                v-else
                v-model="row.counterpartyAccount"
                class="item-vendor"
                maxlength="128"
                placeholder="对方账户"
              />
              <el-input v-model="row.description" class="item-desc" maxlength="500" placeholder="用途说明" />
              <template v-if="createForm.paymentType === 'reimbursement'">
                <el-input v-model="row.invoiceNo" class="item-invoice" maxlength="128" placeholder="发票号" />
                <div class="item-upload">
                  <template v-if="row.receiptFile">
                    <div class="proof-edit">
                      <AuthenticatedImage
                        :src="row.receiptFile"
                        :preview="true"
                        fit="cover"
                        class="proof-edit-img"
                      />
                      <button class="proof-remove" type="button" title="移除凭证" @click="row.receiptFile = ''">
                        <el-icon><Close /></el-icon>
                      </button>
                    </div>
                  </template>
                  <el-upload
                    v-else
                    accept="image/jpeg,image/png,image/gif,image/webp,image/bmp"
                    :show-file-list="false"
                    :http-request="rowUploadHandler(index)"
                  >
                    <div class="proof-drop" title="上传发票/支付凭证图片">
                      <el-icon><Camera /></el-icon>
                      <span>凭证</span>
                    </div>
                  </el-upload>
                </div>
              </template>
              <el-button text type="danger" size="small" @click="removeItemRow(index)">删除</el-button>
            </div>
            <el-button type="primary" plain size="small" @click="addItemRow">
              <el-icon style="margin-right: 4px"><Plus /></el-icon>添加明细行
            </el-button>
            <p class="field-tip">
              合计 <b class="num">{{ formatMoney(itemsTotal) }}</b>；
              {{ createForm.paymentType === 'reimbursement'
                ? '报销行需供应商，且发票号或凭证至少其一'
                : '公卡行需对方账户' }}
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
    <el-drawer v-model="detailVisible" :title="detailData?.order.reimbursementNo || '报销单详情'" size="680px">
      <template v-if="detailData">
        <div class="ticket-hero">
          <div class="ticket-top">
            <span class="ticket-brand">RCDIS FINANCE · 报销凭证</span>
            <el-tag
              :type="reimbursementStatusMeta(detailData.order.status).tagType"
              effect="dark"
              round
            >
              {{ reimbursementStatusMeta(detailData.order.status).label }}
            </el-tag>
          </div>
          <div class="ticket-no num">{{ detailData.order.reimbursementNo }}</div>
          <div class="ticket-amount">
            <span class="ticket-amount-label">报销总金额</span>
            <span class="ticket-amount-value num">¥ {{ formatMoney(detailData.order.totalAmount) }}</span>
          </div>
        </div>

        <div class="ticket-facts">
          <div class="fact">
            <span class="fact-label">项目</span>
            <span class="fact-value">{{ detailData.order.projectName || detailData.order.projectCode || '--' }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">申请人</span>
            <span class="fact-value">{{ detailData.order.applicant || '--' }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">支付方式</span>
            <span class="fact-value">
              <el-tag size="small" :type="paymentTypeMeta(detailData.order.paymentType).tagType" effect="plain">
                {{ paymentTypeMeta(detailData.order.paymentType).label }}
              </el-tag>
            </span>
          </div>
          <div class="fact">
            <span class="fact-label">审批人</span>
            <span class="fact-value">{{ detailData.order.principalInvestigator || '未指定' }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">提交时间</span>
            <span class="fact-value num">{{ detailData.order.submittedAt ? formatDateTime(detailData.order.submittedAt) : '--' }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">审批时间</span>
            <span class="fact-value num">{{ detailData.order.approvedAt ? formatDateTime(detailData.order.approvedAt) : '--' }}</span>
          </div>
        </div>

        <div v-if="detailData.order.rejectReason" class="reject-bar">
          <el-icon><WarningFilled /></el-icon>
          <span>驳回原因：{{ detailData.order.rejectReason }}</span>
        </div>

        <div class="drawer-section">
          <span class="kicker">REIMBURSEMENT ITEMS · 明细</span>
          <el-table :data="detailData.items" size="small" class="drawer-table">
            <el-table-column label="金额" width="105" align="right">
              <template #default="{ row }"><span class="num">{{ formatMoney(row.amount) }}</span></template>
            </el-table-column>
            <el-table-column label="日期" width="100">
              <template #default="{ row }"><span class="num date-text">{{ row.expenseDate || '--' }}</span></template>
            </el-table-column>
            <el-table-column label="供应商 / 对方账户" min-width="130" show-overflow-tooltip>
              <template #default="{ row }">{{ row.vendor || row.counterpartyAccount || '--' }}</template>
            </el-table-column>
            <el-table-column label="用途" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.description || '--' }}</template>
            </el-table-column>
            <el-table-column label="发票 / 凭证" width="130">
              <template #default="{ row }">
                <div v-if="row.invoiceNo || row.receiptFile" class="proof-cell">
                  <AuthenticatedImage
                    v-if="row.receiptFile"
                    :src="row.receiptFile"
                    :preview="true"
                    fit="cover"
                    class="proof-thumb"
                  />
                  <span v-if="row.invoiceNo" class="code-text num">{{ row.invoiceNo }}</span>
                  <el-tag v-else size="small" type="success" effect="plain">有凭证</el-tag>
                </div>
                <el-tag v-else size="small" type="warning" effect="plain">缺</el-tag>
              </template>
            </el-table-column>
            <template #empty><span class="date-text">无明细</span></template>
          </el-table>
          <p class="drawer-total">
            合计 <b class="num">{{ formatMoney(detailData.order.totalAmount) }}</b>
          </p>
        </div>

        <div v-if="detailData.order.paymentType === 'reimbursement'" class="drawer-section">
          <span class="kicker">MATERIAL CHECK</span>
          <div class="check-panel">
            <div class="check-head">
              <el-button size="small" :loading="checking" @click="runCheck(detailData.order.id)">
                <el-icon style="margin-right: 4px"><Refresh /></el-icon>执行材料检查
              </el-button>
              <span v-if="checkResult" class="check-summary" :class="checkResult.pass ? 'pass' : 'fail'">
                {{
                  checkResult.pass
                    ? `材料齐全，共检查 ${checkResult.checkedCount} 条明细`
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
              在「修改草稿」中补齐对应明细的材料后重新检查；标记为提醒的项目不阻断提交
            </p>
          </div>
        </div>

        <div class="drawer-actions">
          <el-button v-if="detailData.order.status === 'draft'" @click="openEdit(detailData.order)">
            修改草稿
          </el-button>
          <el-button
            v-if="detailData.order.paymentType === 'reimbursement' && (detailData.order.status === 'draft' || detailData.order.status === 'rejected')"
            type="primary"
            @click="openSubmit(detailData.order)"
          >
            提交审批
          </el-button>
          <el-button
            v-if="detailData.order.status === 'submitted'"
            type="success"
            @click="openApprove(detailData.order)"
          >
            审批通过
          </el-button>
          <el-button
            v-if="detailData.order.status === 'submitted'"
            type="danger"
            plain
            @click="openReject(detailData.order)"
          >
            审批驳回
          </el-button>
          <el-button v-if="canVoid(detailData.order)" type="danger" plain @click="openVoid(detailData.order)">
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
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, type UploadRequestOptions } from 'element-plus'

import AuditDrawer from '@/components/AuditDrawer.vue'
import AuthenticatedImage from '@/components/AuthenticatedImage.vue'
import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import RiskConfirmDialog from '@/components/RiskConfirmDialog.vue'
import { api } from '@/api'
import { toApiError } from '@/api/client'
import type {
  MaterialCheckVO,
  PaymentType,
  ProjectVO,
  ReimbursementDetailVO,
  ReimbursementItemInput,
  ReimbursementVO
} from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useReimbursementsStore } from '@/stores/reimbursements'
import {
  REIMBURSEMENT_STATUS,
  paymentTypeMeta,
  reimbursementStatusMeta
} from '@/utils/constants'
import { formatDateTime, formatMoney } from '@/utils/format'

const reimbursementsStore = useReimbursementsStore()
const authStore = useAuthStore()

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

function canVoid(row: ReimbursementVO): boolean {
  if (row.paymentType === 'public_payment') {
    return row.status === 'approved'
  }
  return row.status === 'draft' || row.status === 'rejected'
}

// ---------- create / edit ----------
const createVisible = ref(false)
const creating = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')
const editingId = ref<number | null>(null)
const editingVersion = ref(0)
const uploadingIndex = ref(-1)
const createForm = reactive({
  projectId: null as number | null,
  applicant: authStore.displayName,
  paymentType: 'reimbursement' as PaymentType
})
const itemRows = ref<ReimbursementItemInput[]>([])

function emptyItemRow(): ReimbursementItemInput {
  return { amount: '', expenseDate: '', vendor: '', invoiceNo: '', receiptFile: '', description: '', counterpartyAccount: '' }
}

function addItemRow() {
  itemRows.value.push(emptyItemRow())
}

function removeItemRow(index: number) {
  itemRows.value.splice(index, 1)
}

const itemsTotal = computed(() => {
  const total = itemRows.value.reduce((sum, row) => sum + (Number(row.amount) || 0), 0)
  return total.toFixed(2)
})

async function handleRowReceiptUpload(index: number, options: UploadRequestOptions) {
  uploadingIndex.value = index
  try {
    const result = await api.uploadReceiptImage(options.file)
    itemRows.value[index].receiptFile = result.url
    ElMessage.success('凭证图片已上传')
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    uploadingIndex.value = -1
  }
}

function rowUploadHandler(index: number) {
  return (options: UploadRequestOptions) => handleRowReceiptUpload(index, options)
}

function openCreate() {
  dialogMode.value = 'create'
  editingId.value = null
  editingVersion.value = 0
  createForm.projectId = null
  createForm.applicant = authStore.displayName
  createForm.paymentType = 'reimbursement'
  itemRows.value = [emptyItemRow()]
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
    createForm.applicant = detail.order.applicant || authStore.displayName
    createForm.paymentType = detail.order.paymentType
    itemRows.value = detail.items.map((item) => ({
      amount: item.amount,
      expenseDate: item.expenseDate || '',
      vendor: item.vendor || '',
      invoiceNo: item.invoiceNo || '',
      receiptFile: item.receiptFile || '',
      description: item.description || '',
      counterpartyAccount: item.counterpartyAccount || ''
    }))
    if (itemRows.value.length === 0) {
      itemRows.value = [emptyItemRow()]
    }
  } catch (error) {
    ElMessage.error(toApiError(error).message)
    createVisible.value = false
  }
}

function validateAndCleanItems(): ReimbursementItemInput[] | null {
  if (itemRows.value.length === 0) {
    ElMessage.warning('请至少添加一条明细')
    return null
  }
  const items: ReimbursementItemInput[] = []
  for (const row of itemRows.value) {
    const amount = Number(row.amount)
    if (!Number.isFinite(amount) || amount <= 0) {
      ElMessage.warning('每条明细金额需大于 0')
      return null
    }
    if (!row.expenseDate) {
      ElMessage.warning('每条明细需选择日期')
      return null
    }
    if (!row.description?.trim()) {
      ElMessage.warning('每条明细需填写用途说明')
      return null
    }
    if (createForm.paymentType === 'public_payment' && !row.counterpartyAccount?.trim()) {
      ElMessage.warning('公卡支付明细需填写对方账户')
      return null
    }
    items.push({
      amount,
      expenseDate: row.expenseDate,
      vendor: row.vendor?.trim() || undefined,
      invoiceNo: row.invoiceNo?.trim() || undefined,
      receiptFile: row.receiptFile || undefined,
      description: row.description.trim(),
      counterpartyAccount: row.counterpartyAccount?.trim() || undefined
    })
  }
  return items
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
  const items = validateAndCleanItems()
  if (!items) return
  creating.value = true
  try {
    const detail = await reimbursementsStore.create({
      projectId: createForm.projectId,
      applicant: createForm.applicant.trim(),
      paymentType: createForm.paymentType,
      items,
      reason: submitNow ? '创建并提交' : '新建报销单',
      submitNow
    })
    const status = detail.order.status
    if (!submitNow) {
      ElMessage.success('报销单已创建并写入审计留痕')
    } else if (status === 'approved') {
      ElMessage.success('公卡支出已提交并入账')
    } else if (status === 'submitted') {
      ElMessage.success('报销单已创建并提交，已推送飞书通知')
    } else {
      ElMessage.warning('材料检查未通过，报销单已保存为草稿，请补齐材料后再提交')
    }
    createVisible.value = false
    reloadFirstPage()
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    creating.value = false
  }
}

async function saveEdit() {
  if (editingId.value === null) return
  if (!createForm.applicant.trim()) {
    ElMessage.warning('请填写申请人')
    return
  }
  const items = validateAndCleanItems()
  if (!items) return
  creating.value = true
  try {
    await reimbursementsStore.update(editingId.value, {
      applicant: createForm.applicant.trim(),
      items,
      reason: '修改报销单草稿',
      version: editingVersion.value
    })
    ElMessage.success('报销单草稿已更新')
    createVisible.value = false
    loadList()
    if (detailVisible.value && detailData.value) {
      await refreshDetail(detailData.value.order.id)
    }
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
const riskMode = ref<'submit' | 'approve' | 'reject' | 'void' | 'withdraw'>('submit')
const pendingId = ref<number | null>(null)
const applying = ref(false)

function openSubmit(row: ReimbursementVO) {
  riskMode.value = 'submit'
  pendingId.value = row.id
  riskOperation.value = '提交报销单'
  riskTarget.value = `${row.reimbursementNo} · ${row.projectName || row.projectCode || ''}`
  riskBefore.value = `状态：${reimbursementStatusMeta(row.status).label}`
  riskAfter.value = '状态变更为待审批，冻结项目预算并进入审批流程；材料检查不通过时提交会被拒绝'
  riskVisible.value = true
}

function openWithdraw(row: ReimbursementVO) {
  riskMode.value = 'withdraw'
  pendingId.value = row.id
  riskOperation.value = '撤回提交'
  riskTarget.value = `${row.reimbursementNo} · 合计 ${formatMoney(row.totalAmount)}`
  riskBefore.value = '状态：待审批'
  riskAfter.value = '状态回退为草稿并释放冻结预算，可修改后重新提交；已发出的审批卡片将失效'
  riskVisible.value = true
}

function openApprove(row: ReimbursementVO) {
  riskMode.value = 'approve'
  pendingId.value = row.id
  riskOperation.value = '审批通过'
  riskTarget.value = `${row.reimbursementNo} · 合计 ${formatMoney(row.totalAmount)}`
  riskBefore.value = '状态：待审批'
  riskAfter.value = '状态变更为已通过，冻结预算转为实占'
  riskVisible.value = true
}

function openReject(row: ReimbursementVO) {
  riskMode.value = 'reject'
  pendingId.value = row.id
  riskOperation.value = '审批驳回'
  riskTarget.value = `${row.reimbursementNo} · 合计 ${formatMoney(row.totalAmount)}`
  riskBefore.value = '状态：待审批'
  riskAfter.value = '状态变更为已驳回并释放冻结预算，可补齐材料后重新提交'
  riskVisible.value = true
}

function openVoid(row: ReimbursementVO) {
  riskMode.value = 'void'
  pendingId.value = row.id
  riskOperation.value = '作废报销单'
  riskTarget.value = `${row.reimbursementNo} · 合计 ${formatMoney(row.totalAmount)}`
  riskBefore.value = `状态：${reimbursementStatusMeta(row.status).label}`
  riskAfter.value =
    row.paymentType === 'public_payment'
      ? '状态变更为已作废（不可恢复），并冲销已入账的项目预算'
      : '状态变更为已作废（不可恢复）'
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
      ElMessage.success('报销单已审批通过')
    } else if (riskMode.value === 'reject') {
      await reimbursementsStore.reject(pendingId.value, { reason })
      ElMessage.success('报销单已驳回，可补齐材料后重新提交')
    } else if (riskMode.value === 'withdraw') {
      await reimbursementsStore.withdraw(pendingId.value, { reason })
      ElMessage.success('报销单已撤回为草稿，可修改后重新提交')
    } else {
      await reimbursementsStore.voidOrder(pendingId.value, { reason })
      ElMessage.success('报销单已作废')
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

</script>

<style scoped lang="scss">
.reimbursements-page {
  max-width: 1440px;
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
  transition: transform 0.18s ease;

  &:hover {
    transform: scale(1.12);
    box-shadow: 0 4px 12px rgba(31, 56, 158, 0.22);
  }
}

.proof-more {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.create-form {
  .create-row {
    display: flex;
    gap: 16px;
  }
}

.items-editor {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.item-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px;
  border: 1px solid var(--rc-line);
  border-radius: 10px;
  background: #ffffff;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;

  &:hover {
    border-color: var(--rc-primary);
    box-shadow: 0 2px 10px rgba(47, 84, 235, 0.07);
  }
}

.item-amount {
  width: 120px;
}

.item-date {
  width: 140px;
}

.item-vendor {
  flex: 1;
  min-width: 140px;
}

.item-desc {
  flex: 2;
  min-width: 180px;
}

.item-invoice {
  width: 150px;
}

.item-upload {
  display: flex;
  align-items: center;
  gap: 8px;
}

.proof-edit {
  position: relative;
  width: 56px;
  height: 42px;
  border: 1px solid var(--rc-line);
  border-radius: 6px;
  overflow: hidden;
  background: #f2f4f8;
}

.proof-edit-img {
  width: 100%;
  height: 100%;
}

.proof-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 1px solid var(--rc-line);
  border-radius: 50%;
  background: #ffffff;
  color: var(--rc-text-muted);
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.1);
  transition: all 0.15s ease;

  &:hover {
    color: var(--rc-danger);
    border-color: var(--rc-danger);
  }
}

.proof-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1px;
  width: 56px;
  height: 42px;
  border: 1px dashed var(--rc-line);
  border-radius: 6px;
  color: var(--rc-text-faint);
  font-size: 10.5px;
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    color: var(--rc-primary);
    border-color: var(--rc-primary);
    background: var(--el-color-primary-light-9);
  }
}

.field-tip {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--rc-text-muted);
}

.drawer-section {
  margin-bottom: 24px;

  .kicker {
    display: block;
    margin-bottom: 10px;
  }
}

// ---------- 票据抬头 ----------
.ticket-hero {
  position: relative;
  overflow: hidden;
  padding: 16px 20px 18px;
  border-radius: 14px;
  color: #ffffff;
  background: linear-gradient(135deg, #16296b 0%, #2f54eb 56%, #4c6ef5 100%);
  box-shadow: 0 14px 30px rgba(31, 56, 158, 0.26);

  // 斜纹纹理：给票据加一层印刷质感
  &::after {
    content: '';
    position: absolute;
    inset: 0;
    background: repeating-linear-gradient(-45deg, rgba(255, 255, 255, 0.055) 0 2px, transparent 2px 14px);
    pointer-events: none;
  }
}

.ticket-top {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ticket-brand {
  font-size: 10.5px;
  letter-spacing: 0.14em;
  opacity: 0.85;
}

.ticket-no {
  position: relative;
  z-index: 1;
  margin-top: 12px;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 0.05em;
}

.ticket-amount {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-top: 10px;
}

.ticket-amount-label {
  font-size: 11px;
  opacity: 0.82;
}

.ticket-amount-value {
  font-size: 27px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.ticket-facts {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin: 14px 0 18px;
}

.fact {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
  padding: 9px 12px;
  border: 1px solid var(--rc-line);
  border-radius: 10px;
  background: #fbfcff;
}

.fact-label {
  font-size: 10.5px;
  letter-spacing: 0.08em;
  color: var(--rc-text-faint);
}

.fact-value {
  font-size: 13px;
  color: var(--rc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.reject-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 18px;
  padding: 10px 12px;
  border: 1px solid #fecaca;
  border-radius: 10px;
  background: #fef2f2;
  color: var(--rc-danger);
  font-size: 12.5px;
}

.drawer-table {
  width: 100%;

  :deep(.el-table__header th) {
    background: #f7f9fd;
    color: var(--rc-text-secondary);
    font-weight: 600;
  }

  :deep(.proof-thumb) {
    transition: transform 0.18s ease;

    &:hover {
      transform: scale(1.1);
      box-shadow: 0 4px 12px rgba(31, 56, 158, 0.22);
    }
  }
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
  position: relative;
  border: 1px solid var(--rc-line);
  border-left: 3px solid #b9c8f2;
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

// ---------- 抽屉/弹窗容器细节 ----------
:deep(.el-drawer__header) {
  margin-bottom: 12px;
  font-weight: 600;
  color: var(--rc-text);
}

:deep(.el-drawer__body) {
  padding-top: 8px;
}
</style>
