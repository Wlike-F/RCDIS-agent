<template>
  <div class="feishu-page">
    <PageHeader
      kicker="SETTINGS"
      title="飞书通知"
      description="管理机器人配置状态、测试投递与通知出箱记录"
    >
      <template #actions>
        <el-button :loading="refreshing" @click="refreshAll">
          <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新
        </el-button>
      </template>
    </PageHeader>

    <div class="feishu-grid">
      <section class="rc-card send-card">
        <div class="panel-head">
          <div>
            <span class="kicker">OUTBOUND MESSAGE</span>
            <h3>发送测试消息</h3>
          </div>
          <el-tag :type="feishuChannelMeta(activeChannel).tagType" effect="light">
            {{ feishuChannelMeta(activeChannel).label }}
          </el-tag>
        </div>

        <el-alert
          v-if="configError"
          type="error"
          :title="configError"
          show-icon
          :closable="false"
          class="section-alert"
        >
          <el-button size="small" type="primary" plain @click="loadConfig">重试</el-button>
        </el-alert>

        <el-form label-position="top" class="send-form">
          <el-form-item label="服务端发送模式">
            <div class="mode-readonly">
              <span class="mode-name">{{ activeModeLabel }}</span>
              <span class="mode-desc">{{ config?.message || '正在读取服务端配置' }}</span>
            </div>
          </el-form-item>

          <template v-if="usesReceiveId">
            <el-form-item label="接收方类型">
              <el-select v-model="sendForm.receiveIdType" style="width: 100%">
                <el-option v-for="item in RECEIVE_ID_TYPES" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
            <el-form-item label="接收方 ID">
              <el-input
                v-model="sendForm.receiveId"
                :placeholder="receiveIdPlaceholder"
                clearable
              />
              <p class="field-tip">
                {{ defaultReceiveText }}
              </p>
            </el-form-item>
          </template>

          <el-form-item label="消息内容">
            <el-input
              v-model="sendForm.text"
              type="textarea"
              :rows="6"
              maxlength="500"
              show-word-limit
            />
          </el-form-item>

          <el-form-item label="幂等键">
            <div class="idem-row">
              <el-input v-model="sendForm.idempotencyKey" class="mono" />
              <el-button title="重新生成幂等键" @click="regenerateIdempotencyKey">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </div>
            <p class="field-tip">相同幂等键只会投递一次，重复请求会返回已有通知记录。</p>
          </el-form-item>

          <div class="send-actions">
            <el-button type="primary" :loading="sending" @click="send">
              <el-icon v-if="!sending" style="margin-right: 6px"><Promotion /></el-icon>发送
            </el-button>
            <el-button @click="resetMessage">重置内容</el-button>
          </div>
        </el-form>

        <div v-if="result" class="send-result">
          <span class="kicker">LAST RESPONSE</span>
          <div class="result-rows">
            <div class="result-row"><span>记录 ID</span><b class="num">#{{ result.notificationId }}</b></div>
            <div class="result-row">
              <span>状态</span>
              <el-tag size="small" :type="notificationStatusMeta(result.status).tagType" effect="light">
                {{ notificationStatusMeta(result.status).label }}
              </el-tag>
            </div>
            <div class="result-row"><span>幂等</span><b>{{ result.duplicate ? '重复提交，未再次投递' : '新投递' }}</b></div>
            <div class="result-row"><span>目标</span><b class="num">{{ result.target }}</b></div>
            <div class="result-row"><span>发送时间</span><b class="num">{{ formatDateTime(result.sentAt) }}</b></div>
          </div>
        </div>
      </section>

      <aside class="side-stack">
        <section class="rc-card config-card">
          <div class="panel-head">
            <div>
              <span class="kicker">CONFIG STATUS</span>
              <h3>服务端配置</h3>
            </div>
            <el-skeleton v-if="configLoading" :rows="1" animated class="mini-skeleton" />
            <el-tag v-else :type="configStatusTag" effect="light">{{ config?.status || 'UNKNOWN' }}</el-tag>
          </div>

          <div v-if="config" class="config-fields">
            <div class="config-field">
              <span>启用状态</span>
              <b>{{ config.enabled ? '已启用' : '未启用' }}</b>
            </div>
            <div class="config-field">
              <span>客户端类型</span>
              <b class="mono">{{ config.clientType }}</b>
            </div>
            <div class="config-field">
              <span>默认接收方</span>
              <b class="mono">{{ config.maskedDefaultReceiveId || '--' }}</b>
            </div>
            <div class="config-field">
              <span>接收方类型</span>
              <b class="mono">{{ config.defaultReceiveIdType || '--' }}</b>
            </div>
            <div class="config-field">
              <span>App 凭证</span>
              <b>{{ appCredentialText }}</b>
            </div>
            <div class="config-field">
              <span>Webhook</span>
              <b>{{ config.webhookConfigured ? '已配置' : '未配置' }}</b>
            </div>
            <div class="config-field">
              <span>重试次数</span>
              <b class="num">{{ config.maxAttempts }}</b>
            </div>
          </div>
        </section>

        <section class="rc-card template-card">
          <div class="panel-head">
            <div>
              <span class="kicker">TEMPLATES</span>
              <h3>通知模板</h3>
            </div>
            <el-button size="small" type="primary" plain @click="openTemplateCreate">新建模板</el-button>
          </div>

          <el-skeleton v-if="templatesLoading" :rows="4" animated />
          <ul v-else class="template-list">
            <li v-for="template in templates" :key="template.id" class="template-item">
              <div class="template-body">
                <div class="template-title-row">
                  <span class="template-title">{{ template.templateName }}</span>
                  <el-tag size="small" :type="template.messageType === 'interactive' ? 'warning' : 'info'" effect="plain">
                    {{ template.messageType === 'interactive' ? '卡片' : '文本' }}
                  </el-tag>
                  <el-tag v-if="template.builtin" size="small" type="info" effect="plain">内置</el-tag>
                  <el-tag size="small" :type="template.status === 'ACTIVE' ? 'success' : 'danger'" effect="light">
                    {{ template.status === 'ACTIVE' ? '启用' : '停用' }}
                  </el-tag>
                  <el-tag v-if="template.scene" size="small" type="info" effect="plain">{{ template.scene }}</el-tag>
                </div>
                <p class="template-desc">{{ template.description || template.templateCode }}</p>
              </div>
              <div class="template-actions">
                <el-button v-if="template.messageType === 'text'" size="small" @click="applyTemplate(template)">套用</el-button>
                <el-button size="small" @click="openTemplateEdit(template)">编辑</el-button>
                <el-button size="small" @click="toggleTemplate(template)">
                  {{ template.status === 'ACTIVE' ? '停用' : '启用' }}
                </el-button>
                <el-button v-if="!template.builtin" size="small" type="danger" plain @click="removeTemplate(template)">删除</el-button>
              </div>
            </li>
          </ul>
        </section>
      </aside>
    </div>

    <section class="rc-card history-card">
      <div class="history-head">
        <div>
          <span class="kicker">NOTIFICATION OUTBOX</span>
          <h3>发送记录</h3>
        </div>
        <div class="history-actions">
          <el-input v-model="historyKeyword" class="history-search" placeholder="搜索目标、幂等键或内容" clearable>
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="historyStatus" class="history-filter" placeholder="全部状态" clearable>
            <el-option
              v-for="(meta, key) in NOTIFICATION_STATUS"
              :key="key"
              :label="meta.label"
              :value="key"
            />
          </el-select>
          <el-select v-model="historyChannel" class="history-filter" placeholder="全部通道" clearable>
            <el-option
              v-for="(meta, key) in FEISHU_CHANNEL_LABELS"
              :key="key"
              :label="meta.label"
              :value="key"
            />
          </el-select>
          <el-button :loading="historyLoading" @click="loadHistory">
            <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新
          </el-button>
        </div>
      </div>

      <el-alert
        v-if="historyError"
        type="error"
        :title="historyError"
        show-icon
        :closable="false"
        class="section-alert"
      >
        <el-button size="small" type="primary" plain @click="loadHistory">重试</el-button>
      </el-alert>
      <el-table v-else v-loading="historyLoading" :data="historyRecords" class="history-table">
        <el-table-column label="记录" width="90">
          <template #default="{ row }">
            <span class="num">#{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="notificationStatusMeta(row.status).tagType" effect="light">
              {{ notificationStatusMeta(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="通道" width="130">
          <template #default="{ row }">
            <el-tag size="small" :type="feishuChannelMeta(row.channel).tagType" effect="plain">
              {{ feishuChannelMeta(row.channel).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="目标" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.target }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">
            {{ notificationText(row) }}
          </template>
        </el-table-column>
        <el-table-column label="幂等键" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mono">{{ row.idempotencyKey }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span class="num">{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发送时间" width="150">
          <template #default="{ row }">
            <span class="num">{{ formatDateTime(row.sentAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="错误" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="error-text">{{ row.errorMessage || '--' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyBlock icon="Bell" title="暂无通知记录" description="发送测试消息后会在这里看到出箱记录" />
        </template>
      </el-table>

      <el-pagination
        v-if="!historyError && historyTotal > 0"
        v-model:current-page="historyCurrent"
        v-model:page-size="historySize"
        :page-sizes="[10, 20, 50, 100]"
        :total="historyTotal"
        class="history-pagination"
        layout="total, sizes, prev, pager, next"
        @current-change="loadHistory"
        @size-change="handleHistorySizeChange"
      />
    </section>

    <el-dialog
      v-model="templateDialogVisible"
      :title="templateFormMode === 'create' ? '新建通知模板' : '编辑通知模板'"
      width="640px"
      :close-on-click-modal="false"
    >
      <el-form label-position="top">
        <el-form-item v-if="templateFormMode === 'create'" label="模板编码" required>
          <el-input v-model="templateForm.templateCode" maxlength="64" placeholder="字母/数字/下划线，如 BUDGET_WARNING_CUSTOM" />
        </el-form-item>
        <el-form-item v-else label="模板编码">
          <el-input :model-value="templateForm.templateCode" disabled />
        </el-form-item>
        <el-form-item label="模板名称" required>
          <el-input v-model="templateForm.templateName" maxlength="128" />
        </el-form-item>
        <div class="template-form-row">
          <el-form-item label="场景">
            <el-input v-model="templateForm.scene" maxlength="64" placeholder="如：预算预警" />
          </el-form-item>
          <el-form-item label="消息类型" required>
            <el-select v-model="templateForm.messageType" style="width: 100%">
              <el-option label="文本 text" value="text" />
              <el-option label="卡片 interactive" value="interactive" />
            </el-select>
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="templateForm.status" style="width: 100%">
              <el-option label="启用 ACTIVE" value="ACTIVE" />
              <el-option label="停用 DISABLED" value="DISABLED" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="描述">
          <el-input v-model="templateForm.description" maxlength="500" />
        </el-form-item>
        <el-form-item label="模板内容" required>
          <el-input
            v-model="templateForm.content"
            type="textarea"
            :rows="10"
            :placeholder="templateForm.messageType === 'interactive' ? '飞书卡片 JSON，占位符使用 {key}' : '文本正文，占位符使用 {key}'"
            class="mono"
          />
          <p class="field-tip">占位符统一使用 {key} 语法，发送时用业务上下文替换；卡片类型必须是合法 JSON。</p>
        </el-form-item>
        <el-form-item label="操作原因">
          <el-input v-model="templateForm.reason" maxlength="500" placeholder="写入审计日志" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="templateDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="templateSaving" @click="saveTemplate">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { api } from '@/api'
import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import type {
  FeishuConfigStatusVO,
  FeishuMessageResponse,
  FeishuNotificationTemplateVO,
  NotificationOutboxPageRequest,
  NotificationOutboxVO
} from '@/api/types'
import {
  FEISHU_CHANNEL_LABELS,
  NOTIFICATION_STATUS,
  RECEIVE_ID_TYPES,
  feishuChannelMeta,
  notificationStatusMeta
} from '@/utils/constants'
import { formatDateTime, randomId } from '@/utils/format'

interface NotificationPayload {
  text?: unknown
}

const config = ref<FeishuConfigStatusVO | null>(null)
const templates = ref<FeishuNotificationTemplateVO[]>([])
const historyRecords = ref<NotificationOutboxVO[]>([])
const historyTotal = ref(0)
const historyCurrent = ref(1)
const historySize = ref(10)
const historyStatus = ref<string | null>(null)
const historyChannel = ref<string | null>(null)
const historyKeyword = ref('')
const configLoading = ref(false)
const templatesLoading = ref(false)
const historyLoading = ref(false)
const refreshing = ref(false)
const sending = ref(false)
const configError = ref<string | null>(null)
const historyError = ref<string | null>(null)
const result = ref<FeishuMessageResponse | null>(null)

const sendForm = reactive({
  receiveIdType: 'chat_id',
  receiveId: '',
  text: '【RCDIS Agent】飞书通知链路测试，当前用于验证服务端配置、幂等控制与出箱记录。',
  idempotencyKey: randomId()
})

let historySearchTimer: ReturnType<typeof window.setTimeout> | undefined

const activeChannel = computed(() => config.value?.channel || 'FEISHU_NOOP')

const usesReceiveId = computed(() => {
  return activeChannel.value === 'FEISHU_APP'
    || (activeChannel.value === 'FEISHU_NOOP' && config.value?.clientType === 'app')
})

const activeModeLabel = computed(() => {
  return feishuChannelMeta(activeChannel.value).label
})

const receiveIdPlaceholder = computed(() => {
  if (config.value?.maskedDefaultReceiveId) {
    return `留空使用默认接收方 ${config.value.maskedDefaultReceiveId}`
  }
  return 'chat_id / open_id / user_id'
})

const defaultReceiveText = computed(() => {
  if (config.value?.defaultReceiveIdConfigured) {
    return '已配置默认接收方，留空时服务端会自动使用默认群或用户。'
  }
  return '未配置默认接收方，启用自建应用机器人时必须手动填写接收方 ID。'
})

const appCredentialText = computed(() => {
  if (!config.value) return '--'
  return config.value.appIdConfigured && config.value.appSecretConfigured ? '已配置' : '未配置'
})

const configStatusTag = computed(() => {
  if (!config.value) return 'info'
  if (config.value.status === 'READY') return 'success'
  if (config.value.status === 'INCOMPLETE') return 'warning'
  if (config.value.status === 'INVALID') return 'danger'
  return 'info'
})

function buildHistoryRequest(): NotificationOutboxPageRequest {
  const keyword = historyKeyword.value.trim()
  return {
    current: historyCurrent.value,
    size: historySize.value,
    status: historyStatus.value || undefined,
    channel: historyChannel.value || undefined,
    keyword: keyword || undefined
  }
}

async function loadConfig() {
  configLoading.value = true
  configError.value = null
  try {
    config.value = await api.getFeishuConfig()
    if (config.value.defaultReceiveIdType) {
      sendForm.receiveIdType = config.value.defaultReceiveIdType
    }
  } catch (error) {
    config.value = null
    configError.value = error instanceof Error ? error.message : '读取飞书配置失败'
  } finally {
    configLoading.value = false
  }
}

async function loadTemplates() {
  templatesLoading.value = true
  try {
    templates.value = await api.listFeishuTemplates()
  } catch (error) {
    templates.value = []
    ElMessage.error(error instanceof Error ? error.message : '读取通知模板失败')
  } finally {
    templatesLoading.value = false
  }
}

async function loadHistory() {
  historyLoading.value = true
  historyError.value = null
  try {
    const page = await api.listFeishuNotifications(buildHistoryRequest())
    historyRecords.value = page.records
    historyTotal.value = page.total
  } catch (error) {
    historyRecords.value = []
    historyTotal.value = 0
    historyError.value = error instanceof Error ? error.message : '读取通知记录失败'
  } finally {
    historyLoading.value = false
  }
}

async function refreshAll() {
  refreshing.value = true
  try {
    await Promise.all([loadConfig(), loadTemplates(), loadHistory()])
  } finally {
    refreshing.value = false
  }
}

function handleHistorySizeChange() {
  historyCurrent.value = 1
  void loadHistory()
}

function applyTemplate(template: FeishuNotificationTemplateVO) {
  sendForm.text = template.content
}

const templateDialogVisible = ref(false)
const templateFormMode = ref<'create' | 'edit'>('create')
const templateEditingId = ref<number | null>(null)
const templateEditingVersion = ref(0)
const templateSaving = ref(false)

const templateForm = reactive({
  templateCode: '',
  templateName: '',
  scene: '',
  description: '',
  messageType: 'text',
  content: '',
  status: 'ACTIVE',
  reason: ''
})

function resetTemplateForm() {
  templateForm.templateCode = ''
  templateForm.templateName = ''
  templateForm.scene = ''
  templateForm.description = ''
  templateForm.messageType = 'text'
  templateForm.content = ''
  templateForm.status = 'ACTIVE'
  templateForm.reason = ''
}

function openTemplateCreate() {
  templateFormMode.value = 'create'
  templateEditingId.value = null
  resetTemplateForm()
  templateDialogVisible.value = true
}

function openTemplateEdit(template: FeishuNotificationTemplateVO) {
  templateFormMode.value = 'edit'
  templateEditingId.value = template.id
  templateEditingVersion.value = template.version
  templateForm.templateCode = template.templateCode
  templateForm.templateName = template.templateName
  templateForm.scene = template.scene || ''
  templateForm.description = template.description || ''
  templateForm.messageType = template.messageType
  templateForm.content = template.content
  templateForm.status = template.status
  templateForm.reason = ''
  templateDialogVisible.value = true
}

function validateTemplateForm(): boolean {
  if (templateFormMode.value === 'create' && !/^[A-Za-z0-9_-]{2,64}$/.test(templateForm.templateCode.trim())) {
    ElMessage.warning('模板编码必填，仅支持 2-64 位字母/数字/下划线/短横线')
    return false
  }
  if (!templateForm.templateName.trim()) {
    ElMessage.warning('请填写模板名称')
    return false
  }
  if (!templateForm.content.trim()) {
    ElMessage.warning('请填写模板内容')
    return false
  }
  if (templateForm.messageType === 'interactive') {
    try {
      JSON.parse(templateForm.content)
    } catch (_error) {
      ElMessage.warning('卡片模板内容必须是合法 JSON')
      return false
    }
  }
  return true
}

async function saveTemplate() {
  if (!validateTemplateForm()) return
  templateSaving.value = true
  try {
    if (templateFormMode.value === 'create') {
      await api.createFeishuTemplate({
        templateCode: templateForm.templateCode.trim(),
        templateName: templateForm.templateName.trim(),
        scene: templateForm.scene.trim() || undefined,
        description: templateForm.description.trim() || undefined,
        messageType: templateForm.messageType,
        content: templateForm.content,
        status: templateForm.status,
        reason: templateForm.reason.trim() || undefined
      })
      ElMessage.success('模板已创建')
    } else if (templateEditingId.value != null) {
      await api.updateFeishuTemplate(templateEditingId.value, {
        templateName: templateForm.templateName.trim(),
        scene: templateForm.scene.trim() || undefined,
        description: templateForm.description.trim() || undefined,
        messageType: templateForm.messageType,
        content: templateForm.content,
        status: templateForm.status,
        version: templateEditingVersion.value,
        reason: templateForm.reason.trim() || undefined
      })
      ElMessage.success('模板已更新')
    }
    templateDialogVisible.value = false
    await loadTemplates()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存模板失败')
  } finally {
    templateSaving.value = false
  }
}

async function toggleTemplate(template: FeishuNotificationTemplateVO) {
  try {
    const updated = await api.toggleFeishuTemplateStatus(template.id)
    ElMessage.success(updated.status === 'ACTIVE' ? '模板已启用' : '模板已停用')
    await loadTemplates()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '切换模板状态失败')
  }
}

async function removeTemplate(template: FeishuNotificationTemplateVO) {
  let reason: string
  try {
    const result = await ElMessageBox.prompt(
      `删除后不可恢复，确认删除模板「${template.templateName}」？`,
      '删除通知模板',
      { confirmButtonText: '删除', cancelButtonText: '取消', inputPlaceholder: '请填写删除原因（写入审计）', inputValidator: (value: string) => (value && value.trim() ? true : '删除原因必填') }
    )
    reason = (result.value || '').trim()
  } catch (_error) {
    return
  }
  try {
    await api.deleteFeishuTemplate(template.id, { reason })
    ElMessage.success('模板已删除')
    await loadTemplates()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '删除模板失败')
  }
}

function resetMessage() {
  sendForm.text = '【RCDIS Agent】飞书通知链路测试，当前用于验证服务端配置、幂等控制与出箱记录。'
  regenerateIdempotencyKey()
}

function regenerateIdempotencyKey() {
  sendForm.idempotencyKey = randomId()
}

function validateSendForm(): boolean {
  if (!sendForm.text.trim()) {
    ElMessage.warning('请输入消息内容')
    return false
  }
  if (usesReceiveId.value && !sendForm.receiveId.trim() && !config.value?.defaultReceiveIdConfigured) {
    ElMessage.warning('请输入接收方 ID')
    return false
  }
  return true
}

function buildSendTarget(): string {
  if (usesReceiveId.value) {
    return sendForm.receiveId.trim()
  }
  return 'webhook'
}

async function send() {
  if (!validateSendForm()) return
  sending.value = true
  try {
    result.value = await api.sendFeishuTestMessage({
      target: buildSendTarget(),
      receiveIdType: usesReceiveId.value ? sendForm.receiveIdType : undefined,
      text: sendForm.text.trim(),
      idempotencyKey: sendForm.idempotencyKey.trim() || undefined
    })
    ElMessage.success(result.value.duplicate ? '已命中幂等记录，未重复投递' : '飞书消息已提交')
    regenerateIdempotencyKey()
    historyCurrent.value = 1
    await loadHistory()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '发送失败')
    await loadHistory()
  } finally {
    sending.value = false
  }
}

function notificationText(row: NotificationOutboxVO): string {
  try {
    const payload = JSON.parse(row.payload) as NotificationPayload
    if (typeof payload.text === 'string' && payload.text.trim()) {
      return payload.text
    }
    return row.payload
  } catch (_error) {
    return row.payload
  }
}

watch([historyKeyword, historyStatus, historyChannel], () => {
  if (historySearchTimer) window.clearTimeout(historySearchTimer)
  historySearchTimer = window.setTimeout(() => {
    historyCurrent.value = 1
    void loadHistory()
  }, 250)
})

onMounted(() => {
  void refreshAll()
})

onBeforeUnmount(() => {
  if (historySearchTimer) window.clearTimeout(historySearchTimer)
})
</script>

<style scoped lang="scss">
.feishu-page {
  max-width: 1280px;
  margin: 0 auto;
}

.feishu-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(360px, 0.9fr);
  gap: 16px;
  align-items: start;
}

.send-card,
.config-card,
.template-card,
.history-card {
  padding: 20px;
}

.section-alert {
  margin-bottom: 16px;
}

.mode-readonly {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-height: 38px;
  padding: 8px 12px;
  border: 1px solid var(--rc-line);
  border-radius: 8px;
  background: #f8faff;
}

.mode-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);
  white-space: nowrap;
}

.mode-desc {
  min-width: 0;
  font-size: 12px;
  color: var(--rc-text-muted);
  text-align: right;
}

.idem-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.send-actions {
  display: flex;
  gap: 10px;
}

.send-result {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed var(--rc-line);

  .kicker {
    display: block;
    margin-bottom: 10px;
  }
}

.result-rows {
  display: grid;
  gap: 7px;
}

.result-row {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12.5px;

  span {
    width: 64px;
    flex-shrink: 0;
    color: var(--rc-text-muted);
  }

  b {
    font-weight: 500;
    color: var(--rc-text);
    word-break: break-all;
  }
}

.side-stack {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.mini-skeleton {
  width: 80px;
}

.config-fields {
  display: grid;
  gap: 8px;
}

.config-field {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;
  border-bottom: 1px dashed var(--rc-line);

  &:last-child {
    border-bottom: none;
  }

  span {
    font-size: 12px;
    color: var(--rc-text-muted);
  }

  b {
    font-size: 12.5px;
    font-weight: 500;
    color: var(--rc-text-secondary);
    text-align: right;
    word-break: break-all;
  }
}

.template-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.template-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 11px 0;

  & + .template-item {
    border-top: 1px dashed var(--rc-line);
  }
}

.template-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
  max-width: 190px;
}

.template-form-row {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.template-body {
  min-width: 0;
  flex: 1;
}

.template-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.template-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);
}

.template-desc {
  margin: 5px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--rc-text-muted);
}

.history-card {
  margin-top: 16px;
}

.history-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;

  h3 {
    margin: 6px 0 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--rc-text);
  }
}

.history-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.history-search {
  width: 280px;
}

.history-filter {
  width: 128px;
}

.history-table {
  width: 100%;
}

.history-pagination {
  justify-content: flex-end;
  padding-top: 14px;
}

.error-text {
  color: var(--rc-danger);
}

@media (max-width: 1100px) {
  .feishu-grid {
    grid-template-columns: 1fr;
  }

  .history-head,
  .history-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .history-search,
  .history-filter {
    width: 100%;
  }
}

@media (max-width: 720px) {
  .mode-readonly {
    align-items: flex-start;
    flex-direction: column;
  }

  .mode-desc {
    text-align: left;
  }
}
</style>
