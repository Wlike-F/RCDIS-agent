<template>
  <div class="providers-page">
    <PageHeader
      kicker="SETTINGS"
      title="模型供应商"
      description="供应商与模型配置存放在 PostgreSQL，API 密钥经 AES-256/GCM 加密后入库，前端只展示脱敏状态"
    >
      <template #actions>
        <el-button @click="reload">
          <el-icon style="margin-right: 6px"><Refresh /></el-icon>重新加载
        </el-button>
        <el-button type="primary" @click="openCreate">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>新增供应商
        </el-button>
      </template>
    </PageHeader>

    <el-tabs v-model="activeTab" class="provider-tabs">
      <el-tab-pane label="供应商列表" name="providers">
        <el-skeleton
          v-if="providersStore.loading && !providersStore.loaded"
          :rows="4"
          animated
          class="rc-card page-skeleton"
        />
        <el-alert
          v-else-if="providersStore.error"
          type="error"
          :title="providersStore.error"
          :closable="false"
          show-icon
        >
          <el-button size="small" type="primary" plain @click="reload">重试</el-button>
        </el-alert>
        <div v-else-if="providersStore.records.length === 0" class="rc-card providers-empty">
          <EmptyBlock
            icon="Cpu"
            title="尚未配置模型供应商"
            description="点击右上角新增一个供应商，填写接口地址、密钥与模型名即可接入自定义模型"
          />
        </div>

        <div v-else class="provider-grid">
      <div
        v-for="record in providersStore.records"
        :key="record.id"
        class="provider-card rc-card"
        :class="{ disabled: !record.enabled }"
      >
        <div class="provider-head">
          <div class="provider-title">
            <span class="provider-name">{{ record.name }}</span>
            <el-tag v-if="record.defaultProvider" type="primary" size="small" effect="light">默认</el-tag>
            <el-tag v-if="record.builtin" type="info" size="small" effect="plain">内置</el-tag>
          </div>
          <el-switch
            :model-value="record.enabled"
            :loading="providersStore.isBusy(record.id, 'save')"
            :disabled="record.defaultProvider && record.enabled"
            @change="handleToggle(record)"
          />
        </div>

        <div class="provider-fields">
          <div class="provider-field">
            <span class="pf-label">供应商 ID</span>
            <span class="pf-value mono">{{ record.providerId }}</span>
          </div>
          <div class="provider-field">
            <span class="pf-label">接入协议</span>
            <span class="pf-value mono">{{ record.protocol }}</span>
          </div>
          <div class="provider-field">
            <span class="pf-label">接口地址</span>
            <span class="pf-value mono ellipsis" :title="record.baseUrl">{{ record.baseUrl }}</span>
          </div>
          <div class="provider-field">
            <span class="pf-label">对话路径</span>
            <span class="pf-value mono ellipsis" :title="record.chatCompletionsPath ?? ''">
              {{ record.chatCompletionsPath || '默认' }}
            </span>
          </div>
          <div class="provider-field">
            <span class="pf-label">API 密钥</span>
            <span class="pf-value key-status" :class="record.apiKeyConfigured ? 'ok' : 'missing'">
              <span class="key-dot"></span>{{ keyText(record) }}
            </span>
          </div>
          <div class="provider-field">
            <span class="pf-label">默认模型</span>
            <span class="pf-value mono">{{ record.chatModel || '未配置' }}</span>
          </div>
        </div>

        <div class="provider-models">
          <div class="pm-head">
            <span class="pf-label">模型列表</span>
            <span class="pm-count num">{{ record.models.length }}</span>
          </div>
          <div v-if="record.models.length === 0" class="pm-empty">尚未配置模型</div>
          <div v-else class="pm-list">
            <el-tooltip
              v-for="model in record.models"
              :key="model.id"
              :content="modelTooltip(model)"
              placement="top"
            >
              <el-tag
                size="small"
                class="model-tag"
                :type="model.defaultModel ? 'success' : model.enabled ? 'info' : 'warning'"
                :effect="model.defaultModel ? 'dark' : 'plain'"
                @click="handleSetDefaultModel(record, model)"
              >
                {{ model.displayName || model.modelName }}
                <em v-if="model.capability === 'VISION'" class="vision-mark">视觉</em>
              </el-tag>
            </el-tooltip>
          </div>
        </div>

        <div v-if="record.lastTest" class="provider-test-result" :class="levelOf(record.lastTest.status)">
          <span class="dot"></span>
          <span class="tr-label">{{ providerProbeStatusMeta(record.lastTest.status).label }}</span>
          <span class="tr-message">{{ record.lastTest.message }}</span>
          <span v-if="record.lastTest.latencyMs != null" class="num tr-latency">
            {{ record.lastTest.latencyMs }} ms
          </span>
          <span v-if="record.lastTest.testedAt" class="num tr-time">
            {{ formatTimeShort(record.lastTest.testedAt) }}
          </span>
        </div>

        <div class="provider-actions">
          <el-button
            size="small"
            :loading="providersStore.isBusy(record.id, 'test')"
            :disabled="providersStore.isBusy(record.id) && !providersStore.isBusy(record.id, 'test')"
            @click="handleTest(record, false)"
          >
            <el-icon v-if="!providersStore.isBusy(record.id, 'test')" style="margin-right: 4px">
              <Connection />
            </el-icon>测试连通性
          </el-button>
          <el-button
            size="small"
            :disabled="!record.chatModel || providersStore.isBusy(record.id)"
            @click="handleTest(record, true)"
          >
            <el-icon style="margin-right: 4px"><ChatDotRound /></el-icon>对话验证
          </el-button>
          <el-button
            size="small"
            :loading="providersStore.isBusy(record.id, 'discover')"
            :disabled="providersStore.isBusy(record.id) && !providersStore.isBusy(record.id, 'discover')"
            @click="handleDiscover(record)"
          >
            <el-icon v-if="!providersStore.isBusy(record.id, 'discover')" style="margin-right: 4px">
              <MagicStick />
            </el-icon>拉取模型
          </el-button>
          <el-button v-if="!record.defaultProvider" size="small" @click="handleSetDefault(record)">
            设为默认
          </el-button>
          <el-button size="small" @click="openEdit(record)">
            <el-icon style="margin-right: 4px"><Edit /></el-icon>编辑
          </el-button>
          <el-tooltip
            :disabled="!deleteBlockedReason(record)"
            :content="deleteBlockedReason(record) ?? ''"
            placement="top"
          >
            <span>
              <el-button
                size="small"
                type="danger"
                plain
                :disabled="Boolean(deleteBlockedReason(record))"
                :loading="providersStore.isBusy(record.id, 'remove')"
                @click="handleDelete(record)"
              >
                <el-icon><Delete /></el-icon>
              </el-button>
            </span>
          </el-tooltip>
        </div>
      </div>
    </div>
      </el-tab-pane>

      <el-tab-pane label="接入协议" name="protocols">
        <el-card shadow="never" class="protocol-card rc-card">
          <div v-for="protocol in providersStore.protocols" :key="protocol.code" class="protocol-row">
            <div class="protocol-line">
              <el-tag size="small" :type="protocol.supported ? 'success' : 'info'" effect="plain">
                {{ protocol.label }}
              </el-tag>
              <span class="protocol-code mono">{{ protocol.code }}</span>
              <el-tag v-if="!protocol.supported" size="small" type="warning" effect="light">暂未实现</el-tag>
              <el-tag v-if="!protocol.requiresApiKey" size="small" type="info" effect="light">免密钥</el-tag>
            </div>
            <p class="protocol-desc">{{ protocol.description }}</p>
            <div class="protocol-vendors">
              <el-tag
                v-for="vendor in protocol.compatibleVendors"
                :key="vendor"
                size="small"
                type="info"
                effect="plain"
                class="vendor-tag"
              >
                {{ vendor }}
              </el-tag>
            </div>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="凭证识别" name="ocr">
        <el-card shadow="never" class="rc-card ocr-card">
          <div class="ocr-layout">
            <div class="ocr-intro">
              <div class="ocr-intro-icon">
                <el-icon :size="22"><MagicStick /></el-icon>
              </div>
              <h3 class="ocr-title">凭证智能识别</h3>
              <p class="ocr-desc">
                上传发票或支付截图后，由所选视觉模型自动抽取发票号、金额、日期与销售方，
                预填报销明细并做一致性校验。
              </p>
              <div class="ocr-steps">
                <div class="ocr-step"><span class="step-no num">1</span>上传凭证</div>
                <el-icon class="step-arrow"><ArrowRight /></el-icon>
                <div class="ocr-step"><span class="step-no num">2</span>模型识别</div>
                <el-icon class="step-arrow"><ArrowRight /></el-icon>
                <div class="ocr-step"><span class="step-no num">3</span>预填明细</div>
              </div>
            </div>

            <div class="ocr-divider"></div>

            <div class="ocr-right" v-loading="ocrLoading">
              <div v-if="ocrLoading" class="ocr-skeleton">
                <el-skeleton :rows="3" animated />
              </div>
              <div v-else-if="loadError" class="ocr-error">
                <el-alert type="warning" :title="loadError" :closable="false" show-icon />
                <el-button size="small" type="primary" plain @click="loadOcrConfig">重新加载</el-button>
              </div>

              <!-- 查看态：当前生效配置 -->
              <div v-else-if="!ocrEditing" class="ocr-view">
                <div class="ocr-view-grid">
                  <div class="ocr-view-item">
                    <span class="ov-label">识别状态</span>
                    <el-tag size="small" :type="ocrForm.enabled ? 'success' : 'info'" effect="light">
                      {{ ocrForm.enabled ? '已启用' : '已停用' }}
                    </el-tag>
                  </div>
                  <div class="ocr-view-item">
                    <span class="ov-label">识别供应商</span>
                    <span class="ov-value">{{ ocrProviderName || ocrForm.providerId || '—' }}</span>
                  </div>
                  <div class="ocr-view-item">
                    <span class="ov-label">识别模型</span>
                    <span class="ov-value mono">{{ ocrForm.model || '—' }}</span>
                  </div>
                </div>
                <div class="ocr-footer">
                  <span class="ocr-effective">以上为当前生效配置，保存后立即生效</span>
                  <el-button type="primary" plain @click="startEdit">
                    <el-icon style="margin-right: 4px"><Edit /></el-icon>修改配置
                  </el-button>
                </div>
              </div>

              <!-- 编辑态 -->
              <div v-else class="ocr-form">
                <div class="ocr-field">
                  <div class="ocr-field-head">
                    <span class="ocr-field-label">启用凭证识别</span>
                    <el-switch v-model="ocrForm.enabled" />
                  </div>
                  <p class="ocr-field-hint">关闭后，上传与登记凭证不再触发自动识别</p>
                </div>
                <div class="ocr-field">
                  <span class="ocr-field-label">识别供应商</span>
                  <el-select
                    v-model="ocrForm.providerId"
                    style="width: 100%"
                    placeholder="选择承接识别的供应商"
                  >
                    <el-option
                      v-for="record in providersStore.records"
                      :key="record.providerId"
                      :label="record.name"
                      :value="record.providerId"
                    />
                  </el-select>
                </div>
                <div class="ocr-field">
                  <span class="ocr-field-label">识别模型</span>
                  <el-select
                    v-model="ocrForm.model"
                    style="width: 100%"
                    filterable
                    allow-create
                    default-first-option
                    placeholder="选择或输入视觉模型名"
                  >
                    <el-option
                      v-for="model in ocrProviderModels"
                      :key="model.modelName"
                      :label="`${model.displayName || model.modelName}${model.capability === 'VISION' ? '（视觉）' : ''}`"
                      :value="model.modelName"
                    />
                  </el-select>
                    <p v-if="ocrModelNotVision" class="ocr-field-hint ocr-hint-warn">
                      当前选中的模型未标记为视觉能力，可能无法读取凭证图片。请先在「供应商列表」里把它的能力标为「视觉」。
                    </p>
                  <p class="ocr-field-hint">
                    建议选择具备图片理解能力的视觉模型（例如 qwen-vl 系列、gpt-5.5）
                  </p>
                </div>
                <div class="ocr-footer">
                  <span class="ocr-effective">保存后立即生效，无需重启</span>
                  <div class="ocr-footer-actions">
                    <el-button @click="cancelEdit">取消</el-button>
                    <el-button type="primary" :loading="ocrSaving" @click="saveOcrConfig">
                      <el-icon style="margin-right: 4px"><Check /></el-icon>保存设置
                    </el-button>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId == null ? '新增模型供应商' : '编辑模型供应商'"
      width="720px"
      destroy-on-close
      top="6vh"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="供应商 ID" prop="providerId">
              <el-input
                v-model="form.providerId"
                :disabled="editingId != null"
                placeholder="例如 deepseek，创建后不可修改"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="显示名称" prop="name">
              <el-input v-model="form.name" placeholder="例如 DeepSeek" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="接入协议" prop="protocol">
          <el-select v-model="form.protocol" style="width: 100%" @change="handleProtocolChange">
            <el-option
              v-for="protocol in protocolOptions"
              :key="protocol.code"
              :label="protocol.supported ? protocol.label : `${protocol.label}（暂未实现）`"
              :value="protocol.code"
              :disabled="!protocol.supported"
            />
          </el-select>
          <p v-if="selectedProtocol" class="form-hint">{{ selectedProtocol.description }}</p>
        </el-form-item>

        <el-form-item label="接口地址" prop="baseUrl">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
          <p class="form-hint">
            填服务根地址，不要带 /v1；系统会拼接下方请求路径。若填写了以 /v1 结尾的地址，会自动纠正为根地址。
          </p>
        </el-form-item>

        <el-form-item :label="editingId == null ? 'API 密钥' : 'API 密钥（留空表示不修改）'" prop="apiKey">
          <el-input
            v-model="form.apiKey"
            type="password"
            show-password
            :placeholder="editingId == null ? '加密后存入数据库，不会回传前端' : '当前已配置，留空保持不变'"
          />
          <div v-if="editingId != null" class="key-row">
            <span class="form-hint">当前状态：{{ editingKeyText }}</span>
            <el-checkbox v-model="form.clearApiKey" :disabled="Boolean(form.apiKey)">清除已保存的密钥</el-checkbox>
          </div>
        </el-form-item>

        <el-collapse class="advanced-collapse">
          <el-collapse-item name="advanced">
            <template #title>
              <span class="advanced-title">高级设置（请求路径、超时、采样参数）</span>
            </template>
            <el-row :gutter="16">
              <el-col :span="12">
                <el-form-item label="对话补全路径">
                  <el-input v-model="form.chatCompletionsPath" placeholder="/v1/chat/completions" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="模型列表路径">
                  <el-input v-model="form.modelsPath" placeholder="/v1/models" />
                </el-form-item>
              </el-col>
            </el-row>
            <el-row :gutter="16">
              <el-col :span="8">
                <el-form-item label="超时（秒）">
                  <el-input-number v-model="form.timeoutSeconds" :min="3" :max="180" :step="5" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="默认 temperature">
                  <el-input-number
                    v-model="form.temperature"
                    :min="0"
                    :max="2"
                    :step="0.1"
                    :precision="2"
                    placeholder="不限"
                    style="width: 100%"
                  />
                </el-form-item>
              </el-col>
              <el-col :span="8">
                <el-form-item label="默认 maxTokens">
                  <el-input-number
                    v-model="form.maxTokens"
                    :min="1"
                    :max="32768"
                    :step="256"
                    placeholder="不限"
                    style="width: 100%"
                  />
                </el-form-item>
              </el-col>
            </el-row>
            <el-form-item label="备注说明">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="2"
                maxlength="500"
                show-word-limit
                placeholder="例如：实验室自建的 vLLM 推理服务"
              />
            </el-form-item>
          </el-collapse-item>
        </el-collapse>

        <div class="models-editor">
          <div class="me-head">
            <span class="me-title">模型列表</span>
            <el-button size="small" text type="primary" @click="addModelRow">
              <el-icon style="margin-right: 4px"><Plus /></el-icon>添加模型
            </el-button>
          </div>
          <p class="form-hint">
            至少配置一个模型；点击「默认」标记该供应商的默认对话模型。保存后可在卡片上用「拉取模型」从接口批量导入。
          </p>
          <div v-if="form.models.length === 0" class="me-empty">尚未添加模型</div>
          <div v-for="(row, index) in form.models" :key="index" class="me-row">
            <el-input v-model="row.modelName" placeholder="模型名，如 qwen-plus" class="me-name" />
            <el-input v-model="row.displayName" placeholder="显示名（可选）" class="me-display" />
            <el-select v-model="row.capability" size="small" class="me-capability">
              <el-option label="文本" value="TEXT" />
              <el-option label="视觉" value="VISION" />
              <el-option label="向量" value="EMBEDDING" />
            </el-select>
            <el-tag
              size="small"
              class="me-default"
              :type="index === defaultModelIndex ? 'success' : 'info'"
              :effect="index === defaultModelIndex ? 'dark' : 'plain'"
              @click="defaultModelIndex = index"
            >
              默认
            </el-tag>
            <el-switch v-model="row.enabled" size="small" />
            <el-button link type="danger" @click="removeModelRow(index)">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
        </div>

        <el-row v-if="editingId == null" :gutter="16" class="flag-row">
          <el-col :span="12">
            <el-checkbox v-model="form.enabled">创建后立即启用</el-checkbox>
          </el-col>
          <el-col :span="12">
            <el-checkbox v-model="form.defaultProvider" :disabled="!form.enabled">
              设为默认供应商
            </el-checkbox>
          </el-col>
        </el-row>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">
          {{ editingId == null ? '新增' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type {
  ModelProtocolVO,
  ModelProviderModelVO,
  ModelProviderVO
} from '@/api/types'
import EmptyBlock from '@/components/EmptyBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useProvidersStore } from '@/stores/providers'
import { formatTimeShort } from '@/utils/format'
import { providerProbeStatusMeta } from '@/utils/constants'
import type { OcrConfigVO } from '@/api/types'

interface ModelFormRow {
  id: number | null
  modelName: string
  displayName: string
  enabled: boolean
  capability: string
}

interface ProviderForm {
  providerId: string
  name: string
  protocol: string
  baseUrl: string
  chatCompletionsPath: string
  modelsPath: string
  apiKey: string
  clearApiKey: boolean
  timeoutSeconds: number
  temperature: number | undefined
  maxTokens: number | undefined
  description: string
  enabled: boolean
  defaultProvider: boolean
  version: number
  models: ModelFormRow[]
}

const DEFAULT_PROTOCOL = 'openai-compatible'
const DEFAULT_CHAT_PATH = '/v1/chat/completions'
const DEFAULT_MODELS_PATH = '/v1/models'
const DEFAULT_TIMEOUT_SECONDS = 30

const FALLBACK_PROTOCOLS: ModelProtocolVO[] = [
  {
    code: DEFAULT_PROTOCOL,
    label: 'OpenAI 兼容',
    description: '端点需提供 POST /v1/chat/completions 与 GET /v1/models，使用 Bearer 鉴权。',
    chatCompletionsPath: DEFAULT_CHAT_PATH,
    modelsPath: DEFAULT_MODELS_PATH,
    requiresApiKey: true,
    supported: true,
    compatibleVendors: []
  }
]

const providersStore = useProvidersStore()
const activeTab = ref('providers')

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const editingRecord = ref<ModelProviderVO | null>(null)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const defaultModelIndex = ref(0)

const form = reactive<ProviderForm>(createEmptyForm())

const rules: FormRules = {
  providerId: [
    { required: true, message: '请输入供应商 ID', trigger: 'blur' },
    {
      pattern: /^[A-Za-z][A-Za-z0-9._-]*$/,
      message: '需以字母开头，只能包含字母、数字、点、下划线和中划线',
      trigger: 'blur'
    }
  ],
  name: [{ required: true, message: '请输入显示名称', trigger: 'blur' }],
  protocol: [{ required: true, message: '请选择接入协议', trigger: 'change' }],
  baseUrl: [
    { required: true, message: '请输入接口地址', trigger: 'blur' },
    { pattern: /^https?:\/\/.+/, message: '地址需以 http(s):// 开头', trigger: 'blur' }
  ]
}

const protocolOptions = computed<ModelProtocolVO[]>(() =>
  providersStore.protocols.length > 0 ? providersStore.protocols : FALLBACK_PROTOCOLS
)

const selectedProtocol = computed(
  () => protocolOptions.value.find((item) => item.code === form.protocol) ?? null
)

const editingKeyText = computed(() => {
  const record = editingRecord.value
  if (record == null) return ''
  return record.apiKeyConfigured
    ? `已加密保存${record.apiKeyHint ? `（${record.apiKeyHint}）` : ''}`
    : '未配置'
})

void providersStore.load()
void providersStore.loadProtocols()
void loadOcrConfig()

// ---------- 凭证识别设置（管理员） ----------
const ocrLoading = ref(false)
const ocrSaving = ref(false)
const ocrEditing = ref(false)
const loadError = ref('')
const ocrForm = reactive({ enabled: true, providerId: '', model: '' })
let lastLoaded: { enabled: boolean; providerId: string; model: string } | null = null

const ocrProviderModels = computed(() =>
  providersStore.records.find((record) => record.providerId === ocrForm.providerId)?.models ?? []
)

const ocrProviderName = computed(() =>
  providersStore.records.find((record) => record.providerId === ocrForm.providerId)?.name ?? ''
)

// The model is only usable for receipt recognition when it was marked VISION in the provider list.
const ocrModelNotVision = computed(() => {
  if (!ocrForm.model) return false
  const match = ocrProviderModels.value.find((model) => model.modelName === ocrForm.model)
  return match != null && match.capability !== 'VISION'
})

async function loadOcrConfig() {
  ocrLoading.value = true
  loadError.value = ''
  try {
    const config = await api.getOcrConfig()
    ocrForm.enabled = config.enabled
    ocrForm.providerId = config.providerId
    ocrForm.model = config.model
    lastLoaded = { enabled: config.enabled, providerId: config.providerId, model: config.model }
  } catch (error) {
    loadError.value = toApiError(error).message || '凭证识别配置加载失败'
  } finally {
    ocrLoading.value = false
  }
}

function startEdit() {
  if (lastLoaded) {
    ocrForm.enabled = lastLoaded.enabled
    ocrForm.providerId = lastLoaded.providerId
    ocrForm.model = lastLoaded.model
  }
  ocrEditing.value = true
}

function cancelEdit() {
  ocrEditing.value = false
}

async function saveOcrConfig() {
  if (!ocrForm.providerId) {
    ElMessage.warning('请选择识别供应商')
    return
  }
  if (!ocrForm.model) {
    ElMessage.warning('请选择或输入识别模型')
    return
  }
  ocrSaving.value = true
  try {
    await api.updateOcrConfig({
      enabled: ocrForm.enabled,
      providerId: ocrForm.providerId,
      model: ocrForm.model
    })
    lastLoaded = { enabled: ocrForm.enabled, providerId: ocrForm.providerId, model: ocrForm.model }
    ocrEditing.value = false
    ElMessage.success('凭证识别设置已保存，立即生效')
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    ocrSaving.value = false
  }
}

// 切到该 Tab 时若尚未加载成功（首次进入或曾失败），兑底重新加载
watch(activeTab, (tab) => {
  if (tab === 'ocr' && !ocrLoading.value && lastLoaded == null) {
    void loadOcrConfig()
  }
})

function createEmptyForm(): ProviderForm {
  return {
    providerId: '',
    name: '',
    protocol: DEFAULT_PROTOCOL,
    baseUrl: '',
    chatCompletionsPath: DEFAULT_CHAT_PATH,
    modelsPath: DEFAULT_MODELS_PATH,
    apiKey: '',
    clearApiKey: false,
    timeoutSeconds: DEFAULT_TIMEOUT_SECONDS,
    temperature: undefined,
    maxTokens: undefined,
    description: '',
    enabled: true,
    defaultProvider: false,
    version: 0,
    models: [{ id: null, modelName: '', displayName: '', enabled: true, capability: 'TEXT' }]
  }
}

function toNumber(value: string | number | null | undefined): number | undefined {
  if (value == null || value === '') return undefined
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : undefined
}

function keyText(record: ModelProviderVO): string {
  if (!record.apiKeyConfigured) return '未配置'
  return record.apiKeyHint ? `已加密保存（${record.apiKeyHint}）` : '已加密保存'
}

function levelOf(status: string): string {
  const tagType = providerProbeStatusMeta(status).tagType
  if (tagType === 'success') return 'success'
  if (tagType === 'warning') return 'warning'
  if (tagType === 'info') return 'info'
  return 'error'
}

function modelTooltip(model: ModelProviderModelVO): string {
  const parts = [model.modelName]
  if (model.capability === 'VISION') parts.push('视觉模型（可用于凭证识别）')
  else if (model.capability === 'EMBEDDING') parts.push('向量模型')
  if (model.source === 'DISCOVERED') parts.push('接口自动发现')
  if (!model.enabled) parts.push('已停用')
  parts.push(model.defaultModel ? '点击无效：已是默认模型' : '点击设为默认模型')
  return parts.join(' · ')
}

function deleteBlockedReason(record: ModelProviderVO): string | null {
  if (record.builtin) return '内置供应商不可删除，可将其停用'
  if (record.defaultProvider) return '默认供应商不可删除，请先切换默认'
  return null
}

function reload() {
  void providersStore.load(true)
}

function handleProtocolChange(code: string | number | boolean | undefined) {
  const protocol = protocolOptions.value.find((item) => item.code === code)
  if (protocol == null) return
  form.chatCompletionsPath = protocol.chatCompletionsPath ?? DEFAULT_CHAT_PATH
  form.modelsPath = protocol.modelsPath ?? DEFAULT_MODELS_PATH
}

function addModelRow() {
  form.models.push({ id: null, modelName: '', displayName: '', enabled: true, capability: 'TEXT' })
}

function removeModelRow(index: number) {
  form.models.splice(index, 1)
  if (defaultModelIndex.value >= form.models.length) {
    defaultModelIndex.value = Math.max(form.models.length - 1, 0)
  }
}

function openCreate() {
  editingId.value = null
  editingRecord.value = null
  Object.assign(form, createEmptyForm())
  defaultModelIndex.value = 0
  dialogVisible.value = true
}

function openEdit(record: ModelProviderVO) {
  editingId.value = record.id
  editingRecord.value = record
  Object.assign(form, {
    providerId: record.providerId,
    name: record.name,
    protocol: record.protocol,
    baseUrl: record.baseUrl,
    chatCompletionsPath: record.chatCompletionsPath ?? DEFAULT_CHAT_PATH,
    modelsPath: record.modelsPath ?? DEFAULT_MODELS_PATH,
    apiKey: '',
    clearApiKey: false,
    timeoutSeconds: record.timeoutSeconds ?? DEFAULT_TIMEOUT_SECONDS,
    temperature: toNumber(record.temperature),
    maxTokens: record.maxTokens ?? undefined,
    description: record.description ?? '',
    enabled: record.enabled,
    defaultProvider: record.defaultProvider,
    version: record.version,
    models: record.models.map((model) => ({
      id: model.id,
      modelName: model.modelName,
      displayName: model.displayName ?? '',
      enabled: model.enabled,
      capability: model.capability ?? 'TEXT'
    }))
  })
  if (form.models.length === 0) {
    form.models.push({ id: null, modelName: '', displayName: '', enabled: true, capability: 'TEXT' })
  }
  const defaultIndex = record.models.findIndex((model) => model.defaultModel)
  defaultModelIndex.value = defaultIndex >= 0 ? defaultIndex : 0
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // Resolve the default by name before filtering, so blank rows cannot shift the index.
  const defaultModelName = form.models[defaultModelIndex.value]?.modelName.trim() ?? ''
  const models = form.models
    .filter((row) => row.modelName.trim().length > 0)
    .map((row) => ({
      id: row.id,
      modelName: row.modelName.trim(),
      displayName: row.displayName.trim() || null,
      defaultModel: row.modelName.trim() === defaultModelName,
      capability: row.capability,
      status: row.enabled ? 'ACTIVE' : 'DISABLED'
    }))
  if (models.length === 0) {
    ElMessage.warning('请至少填写一个模型名称')
    return
  }
  const names = models.map((model) => model.modelName)
  if (new Set(names).size !== names.length) {
    ElMessage.error('模型名称不能重复')
    return
  }

  submitting.value = true
  try {
    if (editingId.value == null) {
      await providersStore.create({
        providerId: form.providerId.trim(),
        name: form.name.trim(),
        protocol: form.protocol,
        baseUrl: form.baseUrl.trim(),
        chatCompletionsPath: form.chatCompletionsPath.trim() || null,
        modelsPath: form.modelsPath.trim() || null,
        apiKey: form.apiKey.trim() || null,
        timeoutSeconds: form.timeoutSeconds,
        temperature: form.temperature ?? null,
        maxTokens: form.maxTokens ?? null,
        description: form.description.trim() || null,
        enabled: form.enabled,
        defaultProvider: form.defaultProvider,
        models
      })
      ElMessage.success('供应商已新增并写入数据库')
    } else {
      await providersStore.update(editingId.value, {
        name: form.name.trim(),
        protocol: form.protocol,
        baseUrl: form.baseUrl.trim(),
        chatCompletionsPath: form.chatCompletionsPath.trim() || null,
        modelsPath: form.modelsPath.trim() || null,
        apiKey: form.apiKey.trim() || null,
        clearApiKey: form.clearApiKey,
        timeoutSeconds: form.timeoutSeconds,
        temperature: form.temperature ?? null,
        maxTokens: form.maxTokens ?? null,
        description: form.description.trim() || null,
        status: form.enabled ? 'ACTIVE' : 'DISABLED',
        models,
        version: form.version,
        reason: '在模型供应商页面修改配置'
      })
      ElMessage.success('供应商配置已更新')
    }
    dialogVisible.value = false
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  } finally {
    submitting.value = false
  }
}

async function handleToggle(record: ModelProviderVO) {
  try {
    await providersStore.toggleStatus(record.id)
    ElMessage.success(record.enabled ? '供应商已停用' : '供应商已启用')
  } catch (error) {
    ElMessage.error(toApiError(error).message)
    await providersStore.load(true)
  }
}

async function handleSetDefault(record: ModelProviderVO) {
  try {
    await providersStore.setDefault(record.id)
    ElMessage.success(`已将 ${record.name} 设为默认供应商`)
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  }
}

async function handleSetDefaultModel(record: ModelProviderVO, model: ModelProviderModelVO) {
  if (model.defaultModel) return
  if (!model.enabled) {
    ElMessage.warning('已停用的模型不能设为默认，请先在编辑中启用')
    return
  }
  try {
    await providersStore.setDefaultModel(record.id, model.id)
    ElMessage.success(`已将 ${model.modelName} 设为默认模型`)
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  }
}

async function handleTest(record: ModelProviderVO, probeChat: boolean) {
  try {
    const result = await providersStore.test({
      providerId: record.providerId,
      probeChat
    })
    if (result == null) return
    if (result.success) ElMessage.success(result.message)
    else if (levelOf(result.status) === 'warning') ElMessage.warning(result.message)
    else ElMessage.error(result.message)
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  }
}

async function handleDiscover(record: ModelProviderVO) {
  try {
    const result = await providersStore.discoverModels(record.id, true)
    if (result == null) return
    ElMessage.success(result.message)
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  }
}

async function handleDelete(record: ModelProviderVO) {
  let reason: string
  try {
    const response = await ElMessageBox.prompt(
      `删除后 ${record.name} 及其模型配置将不可用，请填写删除原因`,
      '删除模型供应商',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        inputType: 'textarea',
        inputPlaceholder: '例如：改用其他供应商',
        inputValidator: (value: string) =>
          value != null && value.trim().length >= 2 ? true : '请至少填写 2 个字符的原因'
      }
    )
    reason = String(response.value ?? '').trim()
  } catch {
    return
  }
  try {
    await providersStore.remove(record.id, reason, record.version)
    ElMessage.success('供应商已删除')
  } catch (error) {
    ElMessage.error(toApiError(error).message)
  }
}
</script>

<style scoped lang="scss">
.providers-page {
  max-width: 1440px;
  margin: 0 auto;
}

.provider-tabs {
  margin-top: 4px;

  :deep(.el-tabs__header) {
    margin-bottom: 16px;
  }
}

.protocol-card {
  margin-bottom: 16px;
  padding: 0;
}


.protocol-row + .protocol-row {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--rc-line);
}

.protocol-line {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.protocol-code {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.protocol-desc {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--rc-text-secondary);
}

.protocol-vendors {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.vendor-tag {
  font-size: 11px;
}

.page-skeleton,
.providers-empty {
  padding: 20px;
}

.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(420px, 1fr));
  gap: 16px;
}

.provider-card {
  padding: 18px 20px;
  transition: transform 0.18s ease, box-shadow 0.18s ease, opacity 0.18s ease;

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--rc-shadow-pop);
  }

  &.disabled {
    opacity: 0.62;
  }
}

.provider-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.provider-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex-wrap: wrap;
}

.provider-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--rc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.provider-fields {
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px dashed var(--rc-line);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.provider-field {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

.pf-label {
  width: 76px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--rc-text-muted);
}

.pf-value {
  font-size: 12.5px;
  color: var(--rc-text-secondary);
  min-width: 0;

  &.ellipsis {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.key-status {
  display: inline-flex;
  align-items: center;
  gap: 6px;

  .key-dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
  }

  &.ok .key-dot {
    background: var(--rc-success);
  }

  &.missing .key-dot {
    background: var(--rc-text-faint);
  }
}

.provider-models {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--rc-line);
}

.pm-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pm-count {
  font-size: 12px;
  color: var(--rc-text-faint);
}

.pm-empty,
.me-empty {
  margin-top: 8px;
  font-size: 12px;
  color: var(--rc-text-faint);
}

.pm-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  // A discovered catalogue can hold hundreds of models, so cap the height instead of growing the card.
  max-height: 132px;
  overflow-y: auto;
  padding-right: 4px;
}

.model-tag {
  cursor: pointer;
  font-family: var(--rc-font-mono, monospace);
}

.provider-test-result {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 12px;
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 12px;

  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
    flex-shrink: 0;
    background: currentColor;
  }

  .tr-label {
    font-weight: 600;
    flex-shrink: 0;
  }

  .tr-message {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .tr-latency,
  .tr-time {
    flex-shrink: 0;
    opacity: 0.8;
  }

  &.success {
    background: var(--el-color-success-light-9);
    color: var(--rc-success);
  }

  &.warning {
    background: var(--el-color-warning-light-9);
    color: var(--rc-warning);
  }

  &.error {
    background: var(--el-color-danger-light-9);
    color: var(--rc-danger);
  }

  &.info {
    background: var(--el-color-info-light-9);
    color: var(--rc-text-muted);
  }
}

.provider-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  flex-wrap: wrap;
}

.form-hint {
  margin: 4px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--rc-text-faint);
}

.key-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.advanced-collapse {
  margin-bottom: 12px;
  border-top: none;

  .advanced-title {
    font-size: 12.5px;
    color: var(--rc-text-secondary);
  }
}

.models-editor {
  border-top: 1px dashed var(--rc-line);
  padding-top: 12px;
}

.me-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.me-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);
}

.me-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.me-name {
  flex: 2;
}

.me-display {
  flex: 2;
}

.me-capability {
  width: 92px;
  flex-shrink: 0;
}

.vision-mark {
  margin-left: 4px;
  font-style: normal;
  font-size: 9px;
  opacity: 0.85;
}

.me-default {
  cursor: pointer;
  flex-shrink: 0;
}

.flag-row {
  margin-top: 14px;
}

// ---------- 凭证识别设置 ----------
.ocr-layout {
  display: grid;
  grid-template-columns: 1fr 1px 1.25fr;
  gap: 28px;
  align-items: start;
}

.ocr-divider {
  width: 1px;
  height: 100%;
  min-height: 180px;
  background: var(--rc-line);
}

.ocr-intro-icon {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  margin-bottom: 14px;
  background: var(--el-color-primary-light-9);
  color: var(--rc-primary);
}

.ocr-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--rc-text);
}

.ocr-desc {
  margin: 8px 0 18px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--rc-text-muted);
}

.ocr-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.ocr-step {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 12px 5px 6px;
  border: 1px solid var(--rc-line);
  border-radius: 999px;
  font-size: 11.5px;
  color: var(--rc-text-secondary);
  background: #fbfcff;
}

.step-no {
  display: grid;
  place-items: center;
  width: 17px;
  height: 17px;
  border-radius: 50%;
  background: var(--rc-primary);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
}

.step-arrow {
  color: var(--rc-text-faint);
  font-size: 13px;
}

.ocr-form {
  display: flex;
  flex-direction: column;
  flex: 1;
  gap: 14px;
}

.ocr-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ocr-field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);
}

.ocr-field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ocr-field-hint {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--rc-text-muted);
}

.ocr-hint-warn {
  color: var(--el-color-warning);
}

.ocr-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px dashed var(--rc-line);
}

.ocr-effective {
  font-size: 11.5px;
  color: var(--rc-text-faint);
}

.ocr-right {
  // 两种状态（查看/编辑）共用同一最小高度，切换时不跳
  display: flex;
  flex-direction: column;
  min-height: 290px;
}

.ocr-skeleton {
  padding-top: 6px;
}

.ocr-error {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
}

.ocr-view {
  display: flex;
  flex-direction: column;
  flex: 1;
}

.ocr-view-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding-top: 8px;
}

.ocr-view-item {
  display: flex;
  align-items: center;
  gap: 14px;
}

.ov-label {
  width: 76px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--rc-text-muted);
}

.ov-value {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--rc-text);
}

.ocr-footer-actions {
  display: flex;
  gap: 8px;
}

@media (max-width: 960px) {
  .ocr-layout {
    grid-template-columns: 1fr;
  }

  .ocr-divider {
    display: none;
  }
}
</style>
