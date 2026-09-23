export interface OptionMeta {
  label: string
  tagType: 'success' | 'warning' | 'danger' | 'info' | 'primary'
}

export const PROJECT_STATUS: Record<string, OptionMeta> = {
  ACTIVE: { label: '执行中', tagType: 'success' },
  SUSPENDED: { label: '已暂停', tagType: 'warning' },
  CLOSED: { label: '已结题', tagType: 'info' }
}

export function projectStatusMeta(status?: string | null): OptionMeta {
  if (!status) return { label: '未知', tagType: 'info' }
  return PROJECT_STATUS[status] ?? { label: status, tagType: 'info' }
}

export interface RoadmapItem {
  name: string
  en: string
  description: string
  state: 'done' | 'doing' | 'plan'
}

export const ROADMAP: RoadmapItem[] = [
  {
    name: '工程骨架',
    en: 'Scaffold',
    description: 'Spring Boot 3 + MyBatis-Plus + PostgreSQL 基础架构与统一响应',
    state: 'done'
  },
  {
    name: '经费基础数据',
    en: 'Fund Data',
    description: '项目、预算科目、支出登记与余额同事务扣减',
    state: 'doing'
  },
  {
    name: 'Agent 接入',
    en: 'Agent Tools',
    description: 'Spring AI 工具调用与 SSE 流式对话通道',
    state: 'doing'
  },
  {
    name: '风险确认与审计',
    en: 'Audit',
    description: '高风险操作二次确认、审计日志落库',
    state: 'doing'
  },
  {
    name: '飞书机器人',
    en: 'Feishu Bot',
    description: '出站通知已就绪，入站回调验签与幂等开发中',
    state: 'doing'
  },
  {
    name: '报销材料检查',
    en: 'Reimbursement',
    description: '附件管理、材料完整性检查与报销摘要生成',
    state: 'doing'
  }
]

export const ROADMAP_STATE: Record<RoadmapItem['state'], OptionMeta> = {
  done: { label: '已完成', tagType: 'success' },
  doing: { label: '开发中', tagType: 'warning' },
  plan: { label: '规划中', tagType: 'info' }
}

export interface QuickAction {
  key: string
  /** Element Plus icon component name (globally registered in main.ts). */
  icon: string
  category: string
  title: string
  desc: string
  /** Text placed into the composer when the card is clicked. */
  prompt: string
}

// Generic, project-agnostic entry points: the agent asks which project afterwards, so nothing here
// hard-codes a project name or an amount.
export const QUICK_ACTIONS: QuickAction[] = [
  {
    key: 'budget-balance',
    icon: 'Money',
    category: '预算查询',
    title: '查项目经费余额',
    desc: '看看某个项目还剩多少可用经费',
    prompt: '我想查一下某个项目的经费还剩多少'
  },
  {
    key: 'record-expense',
    icon: 'EditPen',
    category: '支出登记',
    title: '登记一笔支出',
    desc: '把刚发生的一笔费用记录下来',
    prompt: '我要登记一笔新的支出'
  },
  {
    key: 'check-materials',
    icon: 'DocumentChecked',
    category: '报销检查',
    title: '检查报销材料',
    desc: '看看我的报销材料是否齐全',
    prompt: '帮我看看我的报销材料是否齐全'
  },
  {
    key: 'monthly-summary',
    icon: 'DataAnalysis',
    category: '支出摘要',
    title: '生成月度支出摘要',
    desc: '汇总本月各项目的经费执行情况',
    prompt: '帮我生成本月的项目支出摘要'
  }
]

export const FEISHU_SCENARIOS = [
  { title: '预算余额预警', description: '预算科目余额低于阈值时推送提醒' },
  { title: '材料缺失提醒', description: '报销材料检查发现缺件时通知申请人' },
  { title: '审批确认通知', description: '高风险操作等待确认时推送群消息' },
  { title: '经费周期摘要', description: '按日或按周推送项目经费执行摘要' },
  { title: '流程状态通知', description: 'Agent 任务执行进度同步到群聊' }
]

export const RECEIVE_ID_TYPES = ['chat_id', 'open_id', 'user_id', 'union_id', 'email']

export const FEISHU_CHANNEL_LABELS: Record<string, OptionMeta> = {
  FEISHU_WEBHOOK: { label: '自定义机器人', tagType: 'primary' },
  FEISHU_APP: { label: '自建应用机器人', tagType: 'success' },
  FEISHU_NOOP: { label: '本地 Noop', tagType: 'info' }
}

export function feishuChannelMeta(channel?: string | null): OptionMeta {
  if (!channel) return { label: '未知', tagType: 'info' }
  return FEISHU_CHANNEL_LABELS[channel] ?? { label: channel, tagType: 'info' }
}

export const NOTIFICATION_STATUS: Record<string, OptionMeta> = {
  PENDING: { label: '待发送', tagType: 'warning' },
  SENT: { label: '已发送', tagType: 'success' },
  FAILED: { label: '发送失败', tagType: 'danger' }
}

export function notificationStatusMeta(status?: string | null): OptionMeta {
  if (!status) return { label: '未知', tagType: 'info' }
  return NOTIFICATION_STATUS[status] ?? { label: status, tagType: 'info' }
}

export const REIMBURSEMENT_STATUS: Record<string, OptionMeta> = {
  draft: { label: '草稿', tagType: 'info' },
  submitted: { label: '待审批', tagType: 'warning' },
  approved: { label: '已通过', tagType: 'success' },
  rejected: { label: '已驳回', tagType: 'danger' },
  void: { label: '已作废', tagType: 'info' }
}

export function reimbursementStatusMeta(status?: string | null): OptionMeta {
  if (!status) return { label: '未知', tagType: 'info' }
  return REIMBURSEMENT_STATUS[status] ?? { label: status, tagType: 'info' }
}

export const PAYMENT_TYPE: Record<string, OptionMeta> = {
  reimbursement: { label: '报销支付', tagType: 'primary' },
  public_payment: { label: '公卡支付', tagType: 'success' }
}

export function paymentTypeMeta(type?: string | null): OptionMeta {
  if (!type) return { label: '未知', tagType: 'info' }
  return PAYMENT_TYPE[type] ?? { label: type, tagType: 'info' }
}

export const AUDIT_ACTION_LABELS: Record<string, string> = {
  CREATE_RESEARCH_PROJECT: '创建项目',
  UPDATE_RESEARCH_PROJECT: '修改项目',
  DELETE_RESEARCH_PROJECT: '删除项目',
  CREATE_BUDGET_CATEGORY: '创建预算科目',
  UPDATE_BUDGET_CATEGORY: '修改预算科目',
  DELETE_BUDGET_CATEGORY: '删除预算科目',
  CREATE_EXPENSE: '登记支出',
  UPDATE_EXPENSE: '修改支出',
  VOID_EXPENSE: '作废支出',
  SEND_TEST_FEISHU_MESSAGE: '发送飞书测试消息',
  CREATE_REIMBURSEMENT: '创建报销单',
  UPDATE_REIMBURSEMENT: '修改报销单',
  VOID_REIMBURSEMENT: '作废报销单',
  SUBMIT_REIMBURSEMENT: '提交报销单',
  APPROVE_REIMBURSEMENT: '审批通过',
  REJECT_REIMBURSEMENT: '审批驳回',
  CREATE_NOTIFICATION_TEMPLATE: '创建通知模板',
  UPDATE_NOTIFICATION_TEMPLATE: '修改通知模板',
  UPDATE_NOTIFICATION_TEMPLATE_STATUS: '启停通知模板',
  DELETE_NOTIFICATION_TEMPLATE: '删除通知模板',
  CREATE_MODEL_PROVIDER: '新增模型供应商',
  UPDATE_MODEL_PROVIDER: '修改模型供应商',
  DELETE_MODEL_PROVIDER: '删除模型供应商',
  UPDATE_MODEL_PROVIDER_STATUS: '启停模型供应商',
  SET_DEFAULT_MODEL_PROVIDER: '切换默认供应商',
  SET_DEFAULT_MODEL_PROVIDER_MODEL: '切换默认模型',
  TEST_MODEL_PROVIDER: '测试模型连通性',
  DISCOVER_MODEL_PROVIDER_MODELS: '拉取模型列表',
  BIND_FEISHU_APPROVER: '绑定飞书审批人',
  UPDATE_FEISHU_APPROVER_STATUS: '启停飞书审批人',
  UNBIND_FEISHU_APPROVER: '解绑飞书审批人'
}

export const AUDIT_TARGET_LABELS: Record<string, string> = {
  RESEARCH_PROJECT: '科研项目',
  BUDGET_CATEGORY: '预算科目',
  EXPENSE_RECORD: '支出记录',
  FEISHU_BOT: '飞书机器人',
  REIMBURSEMENT_ORDER: '报销单',
  NOTIFICATION_TEMPLATE: '通知模板',
  MODEL_PROVIDER: '模型供应商',
  FEISHU_APPROVER: '飞书审批人'
}

export const PROVIDER_PROBE_STATUS: Record<string, OptionMeta> = {
  SUCCESS: { label: '连通正常', tagType: 'success' },
  AUTH_FAILED: { label: '鉴权失败', tagType: 'danger' },
  NOT_FOUND: { label: '端点不存在', tagType: 'danger' },
  RATE_LIMITED: { label: '限流或额度不足', tagType: 'warning' },
  SERVER_ERROR: { label: '服务端错误', tagType: 'danger' },
  CLIENT_ERROR: { label: '请求被拒绝', tagType: 'warning' },
  TIMEOUT: { label: '连接超时', tagType: 'danger' },
  UNREACHABLE: { label: '网络不可达', tagType: 'danger' },
  INVALID_RESPONSE: { label: '响应格式异常', tagType: 'warning' },
  NOT_READY: { label: '暂不支持', tagType: 'info' }
}

export function providerProbeStatusMeta(status?: string | null): OptionMeta {
  if (!status) return { label: '未测试', tagType: 'info' }
  return PROVIDER_PROBE_STATUS[status] ?? { label: status, tagType: 'info' }
}

export function auditActionLabel(action: string): string {
  return AUDIT_ACTION_LABELS[action] ?? action
}

export function auditTargetLabel(targetType: string): string {
  return AUDIT_TARGET_LABELS[targetType] ?? targetType
}
