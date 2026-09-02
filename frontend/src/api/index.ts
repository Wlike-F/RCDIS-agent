import { apiDelete, apiGet, apiPost, apiPut, apiUpload } from './client'
import type {
  AuditLogPageRequest,
  AuditLogVO,
  BudgetCategoryCreateRequest,
  BudgetCategoryPageRequest,
  BudgetCategoryUpdateRequest,
  BudgetCategoryVO,
  ChatConfirmRequest,
  DeleteRequest,
  ExpenseCreateRequest,
  ExpensePageRequest,
  ExpenseUpdateRequest,
  ExpenseVO,
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
  ReimbursementVO
} from './types'

// Probes wait for the provider's own timeout, which may be far longer than the default 15s.
const PROVIDER_PROBE_TIMEOUT_MS = 200000

export const api = {
  health: () => apiGet<HealthVO>('/api/health'),

  listProjects: (request: ProjectPageRequest) =>
    apiGet<PageResponse<ProjectVO>>('/api/projects', { params: request }),

  getProject: (id: number) => apiGet<ProjectVO>(`/api/projects/${id}`),

  createProject: (payload: ProjectCreateRequest) => apiPost<ProjectVO>('/api/projects', payload),

  updateProject: (id: number, payload: ProjectUpdateRequest) =>
    apiPut<ProjectVO>(`/api/projects/${id}`, payload),

  deleteProject: (id: number, payload: DeleteRequest) =>
    apiDelete<void>(`/api/projects/${id}`, payload),

  listBudgetCategories: (projectId: number, request: BudgetCategoryPageRequest) =>
    apiGet<PageResponse<BudgetCategoryVO>>(`/api/projects/${projectId}/budget-categories`, {
      params: request
    }),

  createBudgetCategory: (projectId: number, payload: BudgetCategoryCreateRequest) =>
    apiPost<BudgetCategoryVO>(`/api/projects/${projectId}/budget-categories`, payload),

  updateBudgetCategory: (projectId: number, id: number, payload: BudgetCategoryUpdateRequest) =>
    apiPut<BudgetCategoryVO>(`/api/projects/${projectId}/budget-categories/${id}`, payload),

  deleteBudgetCategory: (projectId: number, id: number, payload: DeleteRequest) =>
    apiDelete<void>(`/api/projects/${projectId}/budget-categories/${id}`, payload),

  listExpenses: (request: ExpensePageRequest) =>
    apiGet<PageResponse<ExpenseVO>>('/api/expenses', { params: request }),

  getExpense: (id: number) => apiGet<ExpenseVO>(`/api/expenses/${id}`),

  createExpense: (payload: ExpenseCreateRequest) => apiPost<ExpenseVO>('/api/expenses', payload),

  updateExpense: (id: number, payload: ExpenseUpdateRequest) =>
    apiPut<ExpenseVO>(`/api/expenses/${id}`, payload),

  deleteExpense: (id: number, payload: DeleteRequest) =>
    apiDelete<void>(`/api/expenses/${id}`, payload),

  listReimbursements: (request: ReimbursementPageRequest) =>
    apiGet<PageResponse<ReimbursementVO>>('/api/reimbursements', { params: request }),

  getReimbursement: (id: number) => apiGet<ReimbursementDetailVO>(`/api/reimbursements/${id}`),

  createReimbursement: (payload: ReimbursementCreateRequest) =>
    apiPost<ReimbursementDetailVO>('/api/reimbursements', payload),

  updateReimbursement: (id: number, payload: ReimbursementUpdateRequest) =>
    apiPut<ReimbursementDetailVO>(`/api/reimbursements/${id}`, payload),

  voidReimbursement: (id: number, payload: ReimbursementActionRequest) =>
    apiPost<ReimbursementDetailVO>(`/api/reimbursements/${id}/void`, payload),

  listAvailableExpenses: (projectId: number, excludeOrderId?: number) =>
    apiGet<ExpenseVO[]>('/api/reimbursements/available-expenses', {
      params: excludeOrderId == null ? { projectId } : { projectId, excludeOrderId }
    }),

  checkReimbursementMaterials: (id: number) =>
    apiGet<MaterialCheckVO>(`/api/reimbursements/${id}/material-check`),

  submitReimbursement: (id: number, payload: ReimbursementActionRequest) =>
    apiPost<ReimbursementDetailVO>(`/api/reimbursements/${id}/submit`, payload),

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

  confirmChat: (payload: ChatConfirmRequest) => apiPost<RecordValue>('/api/chat/confirm', payload),

  listAuditLogs: (request: AuditLogPageRequest) =>
    apiGet<PageResponse<AuditLogVO>>('/api/audit-logs', { params: request }),

  uploadReceiptImage: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return apiUpload<FileUploadResponse>('/api/files/receipt-image', formData)
  }
}
