export interface ApiResponse<T> {
  success: boolean
  code: string
  message: string
  data: T
  timestamp: string
}

export interface PageResponse<T> {
  current: number
  size: number
  total: number
  pages: number
  records: T[]
}

export interface ModelProviderModelVO {
  id: number
  modelName: string
  displayName: string | null
  temperature: string | number | null
  maxTokens: number | null
  defaultModel: boolean
  source: string
  enabled: boolean
  remark: string | null
  version: number
}

export interface ModelProviderTestResultVO {
  status: string
  message: string | null
  httpStatus: number | null
  latencyMs: number | null
  testedAt: string | null
}

export interface ModelProviderVO {
  id: number
  providerId: string
  name: string
  protocol: string
  baseUrl: string
  chatCompletionsPath: string | null
  modelsPath: string | null
  chatModel: string | null
  models: ModelProviderModelVO[]
  enabled: boolean
  defaultProvider: boolean
  builtin: boolean
  apiKeyConfigured: boolean
  apiKeyHint: string | null
  timeoutSeconds: number | null
  temperature: string | number | null
  maxTokens: number | null
  description: string | null
  lastTest: ModelProviderTestResultVO | null
  version: number
  updatedAt: string | null
}

export interface ModelProviderModelInput {
  id?: number | null
  modelName: string
  displayName?: string | null
  temperature?: number | null
  maxTokens?: number | null
  defaultModel?: boolean
  status?: string
  remark?: string | null
}

export interface ModelProviderCreateRequest {
  providerId: string
  name: string
  protocol: string
  baseUrl: string
  chatCompletionsPath?: string | null
  modelsPath?: string | null
  apiKey?: string | null
  timeoutSeconds?: number | null
  temperature?: number | null
  maxTokens?: number | null
  description?: string | null
  enabled?: boolean
  defaultProvider?: boolean
  models?: ModelProviderModelInput[]
}

export interface ModelProviderUpdateRequest {
  name: string
  protocol: string
  baseUrl: string
  chatCompletionsPath?: string | null
  modelsPath?: string | null
  apiKey?: string | null
  clearApiKey?: boolean
  timeoutSeconds?: number | null
  temperature?: number | null
  maxTokens?: number | null
  description?: string | null
  status?: string
  models?: ModelProviderModelInput[]
  version: number
  reason?: string | null
}

export interface ModelProviderDeleteRequest {
  reason: string
  version: number
}

export interface ModelProviderTestRequest {
  providerId?: string | null
  modelName?: string | null
  probeChat?: boolean
}

export interface ModelProviderTestResponse {
  providerId: string
  configured: boolean
  enabled: boolean
  status: string
  message: string
  success: boolean
  httpStatus: number | null
  latencyMs: number | null
  testedModel: string | null
  discoveredModels: string[]
  testedAt: string | null
}

export interface ModelProtocolVO {
  code: string
  label: string
  description: string
  chatCompletionsPath: string | null
  modelsPath: string | null
  requiresApiKey: boolean
  supported: boolean
  compatibleVendors: string[]
}

export interface ModelProviderDiscoveryRequest {
  persist?: boolean
}

export interface ModelProviderDiscoveryVO {
  providerId: string
  discoveredModels: string[]
  persistedCount: number
  skippedCount: number
  message: string
}

export interface ProjectVO {
  id: number
  projectCode: string
  projectName: string
  principalInvestigator: string | null
  fundingSource: string | null
  totalBudget: string | number
  remainingBudget: string | number
  startDate: string | null
  endDate: string | null
  status: string
  version: number
}

export interface ProjectPageRequest {
  current: number
  size: number
  keyword?: string
  status?: string
}

export interface ProjectCreateRequest {
  projectCode: string
  projectName: string
  principalInvestigator?: string
  fundingSource?: string
  totalBudget: string | number
  startDate?: string
  endDate?: string
  status: string
  quickMode?: boolean
}

export interface ProjectUpdateRequest {
  projectName: string
  principalInvestigator?: string
  fundingSource?: string
  totalBudget: string | number
  startDate?: string
  endDate?: string
  status: string
  version: number
}

export interface DeleteRequest {
  reason: string
  version: number
}

export interface BudgetCategoryVO {
  id: number
  projectId: number
  categoryCode: string
  categoryName: string
  allocatedAmount: string | number
  usedAmount: string | number
  frozenAmount: string | number
  availableAmount: string | number
  status: string
  remark: string | null
  version: number
}

export interface BudgetCategoryPageRequest {
  current: number
  size: number
  keyword?: string
  status?: string
}

export interface BudgetCategoryCreateRequest {
  categoryCode: string
  categoryName: string
  allocatedAmount: string | number
  status: string
  remark?: string
}

export interface BudgetCategoryUpdateRequest {
  categoryName: string
  allocatedAmount: string | number
  status: string
  remark?: string
  version: number
}

export interface ExpenseVO {
  id: number
  projectId: number
  projectCode: string | null
  projectName: string | null
  budgetCategoryId: number
  categoryCode: string | null
  categoryName: string | null
  amount: string | number
  expenseDate: string
  vendor: string | null
  invoiceNo: string | null
  receiptFile: string | null
  description: string
  status: string
  version: number
}

export interface ExpensePageRequest {
  current: number
  size: number
  projectId?: number
  budgetCategoryId?: number
  status?: string
  startDate?: string
  endDate?: string
  keyword?: string
}

export interface ExpenseCreateRequest {
  projectId: number
  budgetCategoryId: number
  amount: string | number
  expenseDate: string
  vendor?: string
  invoiceNo?: string
  receiptFile?: string | null
  description: string
  reason: string
}

export interface ExpenseUpdateRequest {
  projectId: number
  budgetCategoryId: number
  amount: string | number
  expenseDate: string
  vendor?: string
  invoiceNo?: string
  receiptFile?: string | null
  description: string
  reason: string
  version: number
}

export interface ReimbursementVO {
  id: number
  reimbursementNo: string
  projectId: number
  projectCode: string | null
  projectName: string | null
  applicant: string
  principalInvestigator: string | null
  totalAmount: string | number
  itemCount: number
  status: string
  submittedAt: string | null
  approvedAt: string | null
  rejectReason: string | null
  createdAt: string
  version: number
}

export interface ReimbursementItemVO {
  itemId: number
  expenseId: number
  amount: string | number
  expenseDate: string | null
  vendor: string | null
  invoiceNo: string | null
  receiptFile: string | null
  description: string | null
  budgetCategoryId: number | null
  categoryName: string | null
  expenseStatus: string | null
}

export interface ReimbursementDetailVO {
  order: ReimbursementVO
  items: ReimbursementItemVO[]
}

export interface ReimbursementPageRequest {
  current: number
  size: number
  status?: string
  projectId?: number
}

export interface QuickExpenseInput {
  budgetCategoryId: number
  amount: number | string
  expenseDate: string
  description: string
  vendor?: string
  invoiceNo?: string
  receiptFile?: string
}

export interface ReimbursementCreateRequest {
  projectId: number
  applicant: string
  expenseIds?: number[]
  newExpenses?: QuickExpenseInput[]
  reason?: string
  submitNow?: boolean
}

export interface ReimbursementUpdateRequest {
  applicant: string
  expenseIds: number[]
  reason?: string
  version: number
}

export interface ReimbursementActionRequest {
  reason?: string
}

export interface MaterialCheckFinding {
  level: string
  expenseId: number | null
  label: string
  message: string
}

export interface MaterialCheckVO {
  pass: boolean
  checkedCount: number
  findings: MaterialCheckFinding[]
}

export interface FileUploadResponse {
  url: string
}

export interface HealthVO {
  status: string
  application: string
  time: string
}

export interface ChatRequest {
  conversationId?: string
  providerId?: string
  message: string
}

export interface ChatResponse {
  conversationId: string
  content: string
  providerId: string
  modelName: string
}

export interface FeishuTestMessageRequest {
  target: string
  receiveIdType?: string
  text: string
  idempotencyKey?: string
}

export interface FeishuMessageResponse {
  notificationId: number
  idempotencyKey: string
  messageType: string
  target: string
  status: string
  duplicate: boolean
  sentAt: string | null
}

export interface FeishuConfigStatusVO {
  enabled: boolean
  clientType: string
  channel: string
  status: string
  message: string
  openApiBaseUrl: string
  appIdConfigured: boolean
  appSecretConfigured: boolean
  defaultReceiveIdConfigured: boolean
  defaultReceiveIdType: string | null
  maskedDefaultReceiveId: string | null
  webhookConfigured: boolean
  signingSecretConfigured: boolean
  maxAttempts: number
}

export interface FeishuNotificationTemplateVO {
  id: number
  templateCode: string
  templateName: string
  scene: string | null
  description: string | null
  messageType: string
  content: string
  builtin: boolean
  status: string
  version: number
  updatedAt: string | null
}

export interface NotificationTemplateCreateRequest {
  templateCode: string
  templateName: string
  scene?: string
  description?: string
  messageType: string
  content: string
  status?: string
  reason?: string
}

export interface NotificationTemplateUpdateRequest {
  templateName: string
  scene?: string
  description?: string
  messageType: string
  content: string
  status?: string
  version: number
  reason?: string
}

export interface NotificationOutboxVO {
  id: number
  channel: string
  target: string
  messageType: string
  payload: string
  status: string
  idempotencyKey: string
  errorMessage: string | null
  sentAt: string | null
  createdAt: string
  updatedAt: string
}

export interface NotificationOutboxPageRequest {
  current: number
  size: number
  status?: string
  channel?: string
  keyword?: string
}

export interface ChatConfirmRequest {
  conversationId: string
  confirmationId?: string
  approved: boolean
}

export interface AuditLogVO {
  id: number
  actor: string
  tenantId: string
  action: string
  targetType: string
  targetId: string | null
  beforeSnapshot: string | null
  afterSnapshot: string | null
  reason: string | null
  source: string
  conversationId: string | null
  createdAt: string
}

export interface AuditLogPageRequest {
  current: number
  size: number
}

export type ChatSseEventName =
  | 'start'
  | 'token'
  | 'tool_start'
  | 'tool_result'
  | 'requires_confirmation'
  | 'notification_sent'
  | 'error'
  | 'done'

export type RecordValue = Record<string, unknown>
