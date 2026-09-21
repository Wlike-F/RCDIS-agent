import { apiDelete, apiGet, apiPost, apiPut, apiUpload } from './client'
import type {
  AgentAttachmentVO,
  AgentMemoryVO,
  AgentMetricsSummaryVO,
  AgentTaskVO,
  AgentToolVO,
  AgentTurnTraceVO,
  MemorySettingVO,
  SemanticMemoryVO,
  AuditLogPageRequest,
  AuditLogVO,
  AuthLoginRequest,
  AuthLoginResponse,
  ChatConfirmRequest,
  ChatConfirmResponse,
  DeleteRequest,
  FeishuApproverBindRequest,
  FeishuApproverDeleteRequest,
  FeishuApproverVO,
  FeishuChatMemberVO,
  FeishuConfigStatusVO,
  FeishuMessageResponse,
  FeishuNotificationTemplateVO,
  FeishuTestMessageRequest,
  FileUploadResponse,
  HealthVO,
  MaterialCheckVO,
  ModelProtocolVO,
  ModelProviderCreateRequest,
  ModelProviderDeleteRequest,
  ModelProviderDiscoveryRequest,
  ModelProviderDiscoveryVO,
  ModelProviderTestRequest,
  ModelProviderTestResponse,
  ModelProviderUpdateRequest,
  ModelProviderVO,
  NotificationOutboxPageRequest,
  NotificationOutboxVO,
  NotificationTemplateCreateRequest,
  NotificationTemplateUpdateRequest,
  PageResponse,
  ProjectCreateRequest,
  ProjectPageRequest,
  ProjectUpdateRequest,
  ProjectVO,
  RecordValue,
  ReimbursementActionRequest,
  ReimbursementCreateRequest,
  ReimbursementDetailVO,
  ReimbursementPageRequest,
  ReimbursementUpdateRequest,
  ReimbursementVO,
  UserCreateRequest,
  UserPageRequest,
  UserPasswordRequest,
  UserRolesRequest,
  UserVO
} from './types'

// Probes wait for the provider's own timeout, which may be far longer than the default 15s.
const PROVIDER_PROBE_TIMEOUT_MS = 200000

export const api = {
  health: () => apiGet<HealthVO>('/api/health'),

  getAgentMetricsSummary: () =>
    apiGet<AgentMetricsSummaryVO>('/api/admin/agent-metrics/summary'),

  login: (payload: AuthLoginRequest) => apiPost<AuthLoginResponse>('/api/auth/login', payload),

  listUsers: (request: UserPageRequest) =>
    apiGet<PageResponse<UserVO>>('/api/users', { params: request }),

  getUser: (id: number) => apiGet<UserVO>(`/api/users/${id}`),

  createUser: (payload: UserCreateRequest) => apiPost<UserVO>('/api/users', payload),

  updateUserPassword: (id: number, payload: UserPasswordRequest) =>
    apiPut<void>(`/api/users/${id}/password`, payload),

  toggleUserStatus: (id: number) => apiPost<UserVO>(`/api/users/${id}/status`, {}),

  assignUserRoles: (id: number, payload: UserRolesRequest) =>
    apiPut<UserVO>(`/api/users/${id}/roles`, payload),

  listProjects: (request: ProjectPageRequest) =>
    apiGet<PageResponse<ProjectVO>>('/api/projects', { params: request }),

  getProject: (id: number) => apiGet<ProjectVO>(`/api/projects/${id}`),

  createProject: (payload: ProjectCreateRequest) => apiPost<ProjectVO>('/api/projects', payload),

  updateProject: (id: number, payload: ProjectUpdateRequest) =>
    apiPut<ProjectVO>(`/api/projects/${id}`, payload),

  deleteProject: (id: number, payload: DeleteRequest) =>
    apiDelete<void>(`/api/projects/${id}`, payload),

  listReimbursements: (request: ReimbursementPageRequest) =>
    apiGet<PageResponse<ReimbursementVO>>('/api/reimbursements', { params: request }),

  getReimbursement: (id: number) => apiGet<ReimbursementDetailVO>(`/api/reimbursements/${id}`),

  createReimbursement: (payload: ReimbursementCreateRequest) =>
    apiPost<ReimbursementDetailVO>('/api/reimbursements', payload),

  updateReimbursement: (id: number, payload: ReimbursementUpdateRequest) =>
    apiPut<ReimbursementDetailVO>(`/api/reimbursements/${id}`, payload),

  voidReimbursement: (id: number, payload: ReimbursementActionRequest) =>
    apiPost<ReimbursementDetailVO>(`/api/reimbursements/${id}/void`, payload),

  checkReimbursementMaterials: (id: number) =>
    apiGet<MaterialCheckVO>(`/api/reimbursements/${id}/material-check`),

  submitReimbursement: (id: number, payload: ReimbursementActionRequest) =>
    apiPost<ReimbursementDetailVO>(`/api/reimbursements/${id}/submit`, payload),

  withdrawReimbursement: (id: number, payload: ReimbursementActionRequest) =>
    apiPost<ReimbursementDetailVO>(`/api/reimbursements/${id}/withdraw`, payload),

  approveReimbursement: (id: number, payload: ReimbursementActionRequest) =>
    apiPost<ReimbursementDetailVO>(`/api/reimbursements/${id}/approve`, payload),

  rejectReimbursement: (id: number, payload: ReimbursementActionRequest) =>
    apiPost<ReimbursementDetailVO>(`/api/reimbursements/${id}/reject`, payload),

  listProviders: () => apiGet<ModelProviderVO[]>('/api/model-providers'),

  getProvider: (id: number) => apiGet<ModelProviderVO>(`/api/model-providers/${id}`),

  listModelProtocols: () => apiGet<ModelProtocolVO[]>('/api/model-providers/protocols'),

  createProvider: (payload: ModelProviderCreateRequest) =>
    apiPost<ModelProviderVO>('/api/model-providers', payload),

  updateProvider: (id: number, payload: ModelProviderUpdateRequest) =>
    apiPut<ModelProviderVO>(`/api/model-providers/${id}`, payload),

  deleteProvider: (id: number, payload: ModelProviderDeleteRequest) =>
    apiDelete<void>(`/api/model-providers/${id}`, payload),

  toggleProviderStatus: (id: number) =>
    apiPost<ModelProviderVO>(`/api/model-providers/${id}/status`, {}),

  setDefaultProvider: (id: number) =>
    apiPost<ModelProviderVO>(`/api/model-providers/${id}/set-default`, {}),

  setDefaultProviderModel: (id: number, modelId: number) =>
    apiPost<ModelProviderVO>(`/api/model-providers/${id}/models/${modelId}/set-default`, {}),

  discoverProviderModels: (id: number, payload: ModelProviderDiscoveryRequest) =>
    apiPost<ModelProviderDiscoveryVO>(`/api/model-providers/${id}/discover-models`, payload, {
      timeout: PROVIDER_PROBE_TIMEOUT_MS
    }),

  testProvider: (payload: ModelProviderTestRequest) =>
    apiPost<ModelProviderTestResponse>('/api/model-providers/test', payload, {
      timeout: PROVIDER_PROBE_TIMEOUT_MS
    }),

  getFeishuConfig: () => apiGet<FeishuConfigStatusVO>('/api/feishu/config'),

  listFeishuTemplates: () => apiGet<FeishuNotificationTemplateVO[]>('/api/feishu/templates'),

  createFeishuTemplate: (payload: NotificationTemplateCreateRequest) =>
    apiPost<FeishuNotificationTemplateVO>('/api/feishu/templates', payload),

  updateFeishuTemplate: (id: number, payload: NotificationTemplateUpdateRequest) =>
    apiPut<FeishuNotificationTemplateVO>(`/api/feishu/templates/${id}`, payload),

  toggleFeishuTemplateStatus: (id: number) =>
    apiPost<FeishuNotificationTemplateVO>(`/api/feishu/templates/${id}/toggle-status`, {}),

  deleteFeishuTemplate: (id: number, payload: { reason: string }) =>
    apiDelete<void>(`/api/feishu/templates/${id}`, payload),

  listFeishuNotifications: (request: NotificationOutboxPageRequest) =>
    apiGet<PageResponse<NotificationOutboxVO>>('/api/feishu/notifications', { params: request }),

  sendFeishuTestMessage: (payload: FeishuTestMessageRequest) =>
    apiPost<FeishuMessageResponse>('/api/feishu/test-message', payload),

  listFeishuApprovers: () => apiGet<FeishuApproverVO[]>('/api/feishu/approvers'),

  listFeishuChatMembers: () => apiGet<FeishuChatMemberVO[]>('/api/feishu/chat-members'),

  bindFeishuApprover: (payload: FeishuApproverBindRequest) =>
    apiPost<FeishuApproverVO>('/api/feishu/approvers', payload),

  toggleFeishuApproverStatus: (id: number) =>
    apiPost<FeishuApproverVO>(`/api/feishu/approvers/${id}/status`, {}),

  unbindFeishuApprover: (id: number, payload: FeishuApproverDeleteRequest) =>
    apiDelete<void>(`/api/feishu/approvers/${id}`, payload),

  confirmChat: (payload: ChatConfirmRequest) =>
    apiPost<ChatConfirmResponse>('/api/chat/confirm', payload),

  getAgentTask: (id: number) => apiGet<AgentTaskVO>(`/api/agent-tasks/${id}`),

  listAuditLogs: (request: AuditLogPageRequest) =>
    apiGet<PageResponse<AuditLogVO>>('/api/audit-logs', { params: request }),

  listAgentTools: () => apiGet<AgentToolVO[]>('/api/developer/agent-tools'),

  listChatTraces: (conversationId: string) =>
    apiGet<AgentTurnTraceVO[]>('/api/chat/traces', { params: { conversationId } }),

  getChatMemory: (conversationId: string) =>
    apiGet<AgentMemoryVO>('/api/chat/memory', { params: { conversationId } }),

  getMemorySetting: () => apiGet<MemorySettingVO>('/api/agent/memory/setting'),

  updateMemorySetting: (extractEnabled: boolean, injectEnabled: boolean) =>
    apiPut<MemorySettingVO>('/api/agent/memory/setting', { extractEnabled, injectEnabled }),

  listSemanticMemories: () => apiGet<SemanticMemoryVO[]>('/api/agent/memory/list'),

  uploadReceiptImage: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return apiUpload<FileUploadResponse>('/api/files/receipt-image', formData)
  },

  uploadAgentAttachment: (file: File, conversationId?: string) => {
    const formData = new FormData()
    formData.append('file', file)
    if (conversationId) formData.append('conversationId', conversationId)
    return apiUpload<AgentAttachmentVO>('/api/files/agent-attachment', formData)
  }
}
