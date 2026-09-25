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
  capability: string
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

/**
 * Minimal, non-sensitive model option for the chat selector, readable by every authenticated role.
 * Carries no base URL or API key material (unlike {@link ModelProviderVO}).
 */
export interface ChatModelOptionVO {
  providerId: string
  name: string
  chatModel: string | null
  defaultProvider: boolean
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
  usedAmount: string | number
  frozenAmount: string | number
  availableAmount: string | number
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

export type PaymentType = 'reimbursement' | 'public_payment'

export interface ReimbursementItemInput {
  amount: string | number
  expenseDate: string
  vendor?: string
  invoiceNo?: string
  receiptFile?: string | null
  description: string
  counterpartyAccount?: string
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
  invoiceSummary: string
  receiptFiles: string[]
  status: string
  paymentType: PaymentType
  submittedAt: string | null
  approvedAt: string | null
  rejectReason: string | null
  createdAt: string
  version: number
}

// ---------- Overview ----------

export interface OverviewMyProjectVO {
  projectId: number
  projectCode: string
  projectName: string
  status: string
  totalBudget: string | number
  available: string | number
}

export interface OverviewSummaryVO {
  role: 'ADMIN' | 'MEMBER' | string
  displayName: string
  generatedAt: string
  projectCount: number | null
  totalBudget: string | number | null
  enabledProviders: number | null
  totalProviders: number | null
  pendingApprovalCount: number | null
  myDraftCount: number | null
  mySubmittedCount: number | null
  myApprovedCount: number | null
  myRejectedCount: number | null
  mySubmittedAmount: string | number | null
  myApprovedAmount: string | number | null
  myProjectCount: number | null
  myProjectsTotalBudget: string | number | null
  myProjectsAvailable: string | number | null
  myProjects: OverviewMyProjectVO[]
}

export interface ReimbursementItemVO {
  itemId: number
  amount: string | number
  expenseDate: string | null
  vendor: string | null
  invoiceNo: string | null
  receiptFile: string | null
  description: string | null
  counterpartyAccount: string | null
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

export interface ReimbursementCreateRequest {
  projectId: number
  applicant: string
  paymentType: PaymentType
  items: ReimbursementItemInput[]
  reason?: string
  submitNow?: boolean
}

export interface ReimbursementUpdateRequest {
  applicant: string
  items: ReimbursementItemInput[]
  reason?: string
  version: number
}

export interface ReimbursementActionRequest {
  reason?: string
}

export interface MaterialCheckFinding {
  level: string
  itemId: number | null
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
  attachmentIds?: number[]
}

export interface AgentAttachmentVO {
  id: number
  conversationId: string | null
  originalName: string
  url: string
  mime: string | null
  ext: string | null
  sizeBytes: number | null
  kind: string
  extractStatus: string
  extractedLength: number
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

export interface AppNotificationVO {
  id: number
  recipient: string
  type: 'SUBMITTED' | 'APPROVED' | 'REJECTED' | 'VOIDED'
  title: string
  content: string | null
  bizType: string
  bizId: number | null
  read: boolean
  createdAt: string
}

export interface FeishuApproverVO {
  id: number
  openId: string
  userId: string
  userName: string
  role: string
  status: string
  remark: string | null
  version: number
  updatedAt: string | null
}

export interface FeishuChatMemberVO {
  openId: string
  name: string
  bound: boolean
  approverId: number | null
  boundRole: string | null
}

export interface FeishuApproverBindRequest {
  openId: string
  userId: string
  userName: string
  tenantId?: string | null
  role?: string
  remark?: string | null
}

export interface FeishuApproverDeleteRequest {
  reason: string
  version: number
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

export interface ChatConfirmResponse {
  confirmationId: string
  status: string
  executed: boolean
  message: string
}

export interface AgentTaskStepVO {
  id: number
  stepNo: number
  action: string
  targetType: string
  targetId: string
  status: string
  outputJson: string | null
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
}

export interface AgentTaskVO {
  id: number
  conversationId: string
  taskType: string
  title: string
  status: string
  totalSteps: number
  completedSteps: number
  failedSteps: number
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
  createdAt: string
  steps: AgentTaskStepVO[]
}

// ---------- Auth & Users ----------

export interface AuthUser {
  userId: string
  username: string
  tenantId: string
  roles: string[]
}

export interface AuthLoginRequest {
  username: string
  password: string
}

export interface AuthLoginResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
  user: AuthUser
}

export interface UserVO {
  id: number
  username: string
  displayName: string
  tenantId: string
  status: string
  roles: string[]
  lastLoginAt: string | null
  createdAt: string | null
  version: number
}

export interface UserPageRequest {
  current: number
  size: number
  keyword?: string
}

export interface UserCreateRequest {
  username: string
  password: string
  displayName: string
  tenantId?: string
  roles: string[]
}

export interface UserPasswordRequest {
  password: string
}

export interface UserRolesRequest {
  roles: string[]
}

// ---------- Developer console ----------

export interface MemoryRecallVO {
  id: number
  factType: string | null
  content: string
  cosineDistance: number | null
  keywordScore: number | null
  fusedRank: number | null
}

export interface MemoryRetrievalProbeVO {
  query: string
  userId: string
  retrievalEnabled: boolean
  mode: 'hybrid' | 'vector_only' | 'keyword_only' | 'no_match' | string
  vectorAvailable: boolean
  keywordAvailable: boolean
  embeddingProvider: string
  embeddingModel: string
  dimension: number
  alpha: number
  topK: number
  hits: MemoryRecallVO[]
  note: string | null
}

export interface ToolCallTraceVO {
  name: string
  ok: boolean
  durationMs: number
  status: string
}

export interface AgentTurnTraceVO {
  id: number
  conversationId: string
  turnSeq: number
  providerCode: string | null
  modelName: string | null
  status: string
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  firstTokenMs: number | null
  totalMs: number | null
  toolCalls: ToolCallTraceVO[]
  errorMessage: string | null
  createdAt: string
}

export interface SummaryFactVO {
  type: string | null
  text: string | null
  turnSeq: number | null
  key: string | null
}

export interface AgentMemoryVO {
  conversationId: string
  rollingSummary: string | null
  facts: SummaryFactVO[]
  summaryUptoSeq: number | null
  windowMessages: number
  windowTokens: number
  compressCount: number | null
  lastCompressedAt: string | null
  totalMessages: number
}

export interface MemorySettingVO {
  userId: string
  extractEnabled: boolean
  injectEnabled: boolean
  globalExtractEnabled: boolean
  globalInjectEnabled: boolean
}

export interface SemanticMemoryVO {
  id: number
  scope: string | null
  factType: string | null
  content: string
  sourceConversationId: string | null
  sourceSeq: number | null
  hitCount: number | null
  lastHitAt: string | null
  createdAt: string
}

export interface ChatSessionVO {
  conversationId: string
  title: string
  providerCode: string | null
  messageCount: number | null
  lastMessageAt: string | null
  createdAt: string | null
}

export interface ChatMessageVO {
  seq: number
  role: 'user' | 'assistant' | 'tool' | string
  content: string
  providerCode: string | null
  modelName: string | null
  status: string | null
  errorMessage: string | null
  createdAt: string | null
}

export interface OcrConfigVO {
  enabled: boolean
  providerId: string
  model: string
  providers: ModelProviderVO[]
}

export interface EmbeddingConfigVO {
  enabled: boolean
  providerId: string
  model: string
  embeddingsPath: string
  dimension: number
  providers: ModelProviderVO[]
}

export interface AgentToolParamVO {
  name: string
  type: string
  required: boolean
  description: string
}

export interface AgentToolVO {
  name: string
  description: string
  /** READ = query-only, WRITE = mutates data and is confirmation-gated. */
  category: 'READ' | 'WRITE' | string
  requiresConfirmation: boolean
  status: string
  params: AgentToolParamVO[]
}

// ---------- Agent observability ----------

export interface AgentMetricsProviderStatVO {
  providerCode: string
  modelName: string
  turns: number
  totalTokens: number
}

export interface AgentMetricsPeriodVO {
  days: number
  windowStart: string
  windowEnd: string
  turns: number
  doneTurns: number
  errorTurns: number
  timeoutTurns: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  avgFirstTokenMs: number | null
  avgTotalMs: number | null
  toolCallsTotal: number
  toolCallsSuccess: number
  toolSuccessRate: number | null
  costConfigured: boolean
  estimatedCost: number
  providers: AgentMetricsProviderStatVO[]
  generatedAt: string
}

export interface AgentToolMetricVO {
  tool: string
  totalCalls: number
  successfulCalls: number
  failedCalls: number
  successRate: number
  averageDurationMs: number
}

export interface AgentToolMetricsVO {
  totalCalls: number
  successfulCalls: number
  failedCalls: number
  successRate: number
  averageDurationMs: number
  byTool: AgentToolMetricVO[]
}

export interface AgentTokenMetricsVO {
  promptTokens: number
  completionTokens: number
  totalTokens: number
  estimatedCost: number
  costConfigured: boolean
}

export interface AgentMemoryMetricsVO {
  hits: number
  misses: number
  totalInjections: number
  itemsInjected: number
  hitRate: number
}

export interface AgentMetricBreakdownVO {
  total: number
  values: Record<string, number>
}

export interface AgentLatencyMetricsVO {
  sampleCount: number
  averageMs: number
  maxMs: number
}

export interface AgentMetricsSummaryVO {
  generatedAt: string
  tools: AgentToolMetricsVO
  tokens: AgentTokenMetricsVO
  memory: AgentMemoryMetricsVO
  turns: AgentMetricBreakdownVO
  confirmations: AgentMetricBreakdownVO
  taskSteps: AgentMetricBreakdownVO
  recoveries: AgentMetricBreakdownVO
  securityBlocks: AgentMetricBreakdownVO
  firstTokenLatency: AgentLatencyMetricsVO
  turnLatency: AgentLatencyMetricsVO
}
