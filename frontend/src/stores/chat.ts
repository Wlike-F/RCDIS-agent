import { defineStore } from 'pinia'

import { api } from '@/api'
import { streamChat } from '@/api/chat'
import type { AgentTaskVO, ChatRequest, RecordValue } from '@/api/types'
import { randomId } from '@/utils/format'
import { loadJSON, saveJSON } from '@/utils/storage'

import { useProvidersStore } from './providers'

export type MessageRole = 'user' | 'assistant'
export type MessageStatus = 'streaming' | 'done' | 'error'
export type ToolStatus = 'running' | 'done' | 'failed'

export interface ToolCall {
  id: string
  name: string
  status: ToolStatus
  payload: RecordValue | null
  result: RecordValue | null
}

export interface MessageAttachment {
  id: number
  name: string
  kind: string
  url: string
}

export interface Confirmation {
  id: string | null
  operation: string
  targetType: string
  targetId: string
  summary: string
  before: string
  after: string
  reason: string
  scope: string
  raw: RecordValue
  resolved: boolean
  approved: boolean | null
  resolving: boolean
  resultNote: string | null
  resultLevel: 'success' | 'warning' | 'info' | 'error' | null
  taskId: number | null
  task: AgentTaskVO | null
  taskLoadError: string | null
}

export interface ChatMessage {
  id: string
  role: MessageRole
  content: string
  time: string
  status: MessageStatus
  toolCalls: ToolCall[]
  confirmation: Confirmation | null
  attachments: MessageAttachment[]
  error: string | null
  stopped: boolean
  providerId: string | null
  modelName: string | null
}

export interface Conversation {
  id: string
  title: string
  providerId: string | null
  messages: ChatMessage[]
  createdAt: string
  updatedAt: string
}

const ACTIVE_STORAGE_KEY = 'rcdis.chat.active.v1'

interface ChatState {
  conversations: Conversation[]
  activeId: string | null
  streaming: boolean
  sessionsLoaded: boolean
  loadingSessions: boolean
}

// Module-scoped transport state: not persisted, not serializable
let abortController: AbortController | null = null
let manualStop = false

function newMessage(partial: Partial<ChatMessage> & Pick<ChatMessage, 'role'>): ChatMessage {
  return {
    id: randomId(),
    content: '',
    time: new Date().toISOString(),
    status: 'done',
    toolCalls: [],
    confirmation: null,
    attachments: [],
    error: null,
    stopped: false,
    providerId: null,
    modelName: null,
    ...partial
  }
}

function newConversation(providerId: string | null): Conversation {
  const now = new Date().toISOString()
  return {
    id: randomId(),
    title: '新对话',
    providerId,
    messages: [],
    createdAt: now,
    updatedAt: now
  }
}

function stringifyValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

function toolName(payload: RecordValue | null): string {
  if (!payload) return '未知工具'
  const raw = payload.toolName ?? payload.tool ?? payload.name
  return typeof raw === 'string' && raw ? raw : '未知工具'
}

function toConfirmation(payload: RecordValue): Confirmation {
  const targetId = stringifyValue(payload.targetId ?? payload.target_id)
  const targetType = stringifyValue(payload.targetType ?? payload.target_type)
  const parsedTaskId = targetType === 'AGENT_TASK' ? Number(targetId) : Number.NaN
  return {
    id: typeof payload.confirmationId === 'string' ? payload.confirmationId : null,
    operation: stringifyValue(payload.operation ?? payload.action ?? payload.type),
    targetType,
    targetId,
    summary: stringifyValue(payload.summary ?? payload.description ?? payload.message),
    before: stringifyValue(payload.before ?? payload.beforeSnapshot ?? payload.before_snapshot),
    after: stringifyValue(payload.after ?? payload.afterSnapshot ?? payload.after_snapshot),
    reason: stringifyValue(payload.reason),
    scope: stringifyValue(payload.scope ?? payload.impact),
    raw: payload,
    resolved: false,
    approved: null,
    resolving: false,
    resultNote: null,
    resultLevel: null,
    taskId: Number.isSafeInteger(parsedTaskId) && parsedTaskId > 0 ? parsedTaskId : null,
    task: null,
    taskLoadError: null
  }
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => {
    // Conversations live in PostgreSQL (chat_session / chat_message); only the id of the
    // conversation that was open last is remembered locally.
    const persistedActive = loadJSON<string | null>(ACTIVE_STORAGE_KEY, null)
    return {
      conversations: [],
      activeId: typeof persistedActive === 'string' ? persistedActive : null,
      streaming: false,
      sessionsLoaded: false,
      loadingSessions: false
    }
  },
  getters: {
    activeConversation(state): Conversation | null {
      return state.conversations.find((item) => item.id === state.activeId) ?? null
    },
    // Sidebar and default-selection order by last activity, not creation time, so the most
    // recently used conversation is always on top.
    orderedConversations(state): Conversation[] {
      return [...state.conversations].sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1))
    },
    isEmpty: (state) => state.conversations.length === 0
  },
  actions: {
    persist() {
      saveJSON(ACTIVE_STORAGE_KEY, this.activeId)
    },

    async loadSessions() {
      if (this.loadingSessions) return
      this.loadingSessions = true
      try {
        const sessions = await api.listChatSessions()
        const server = sessions.map((session) => ({
          id: session.conversationId,
          title: session.title,
          providerId: session.providerCode,
          messages: [] as ChatMessage[],
          createdAt: session.createdAt ?? new Date().toISOString(),
          updatedAt: session.lastMessageAt ?? session.createdAt ?? new Date().toISOString()
        }))
        // Keep locally created conversations that have not been persisted server-side yet
        // (e.g. an empty new conversation opened a second ago).
        const serverIds = new Set(server.map((item) => item.id))
        const localOnly = this.conversations.filter(
          (item) => !serverIds.has(item.id) && item.messages.length === 0
        )
        this.conversations = [...server, ...localOnly]
        this.sessionsLoaded = true
      } catch (error) {
        console.error('加载会话列表失败', error)
      } finally {
        this.loadingSessions = false
      }
    },

    async loadMessages(id: string) {
      const conversation = this.conversations.find((item) => item.id === id)
      if (!conversation || conversation.messages.length > 0) return
      try {
        const history = await api.listChatMessages(id)
        // Re-read from the reactive array: the local list may have been replaced while loading.
        const target = this.conversations.find((item) => item.id === id)
        if (!target) return
        target.messages = history
          .filter((row) => row.role === 'user' || row.role === 'assistant')
          .map((row) =>
            newMessage({
              role: row.role as MessageRole,
              content: row.content ?? '',
              time: row.createdAt ?? new Date().toISOString(),
              status: row.status === 'ERROR' ? 'error' : 'done',
              error: row.status === 'ERROR' ? row.errorMessage : null,
              providerId: row.providerCode,
              modelName: row.modelName
            })
          )
      } catch (error) {
        console.error('加载会话历史失败', error)
      }
    },

    async initActive() {
      await this.loadSessions()
      if (this.activeId && this.conversations.some((item) => item.id === this.activeId)) {
        await this.loadMessages(this.activeId)
        return
      }
      if (this.conversations.length > 0) {
        this.activeId = this.orderedConversations[0].id
        this.persist()
        await this.loadMessages(this.activeId)
        return
      }
      this.createConversation(null)
    },

    createConversation(providerId: string | null) {
      const conversation = newConversation(providerId)
      this.conversations.unshift(conversation)
      this.activeId = conversation.id
      this.persist()
      return conversation
    },

    ensureConversation(providerId: string | null): Conversation {
      if (this.activeConversation) return this.activeConversation
      return this.createConversation(providerId)
    },

    selectConversation(id: string) {
      this.activeId = id
      this.persist()
      void this.loadMessages(id)
    },

    removeConversation(id: string) {
      const index = this.conversations.findIndex((item) => item.id === id)
      if (index < 0) return
      this.conversations.splice(index, 1)
      if (this.activeId === id) {
        this.activeId = this.orderedConversations[0]?.id ?? null
      }
      this.persist()
      // Server-side soft delete; failures are non-fatal because the list reloads from the server.
      void api.deleteChatSession(id).catch((error) => console.error('删除会话失败', error))
    },

    setConversationProvider(providerId: string) {
      if (this.activeConversation) {
        this.activeConversation.providerId = providerId
        this.persist()
      }
    },

    stop() {
      if (abortController) {
        manualStop = true
        abortController.abort()
      }
    },

    async send(
      text: string,
      extra?: { attachmentIds?: number[]; attachments?: MessageAttachment[] }
    ) {
      const trimmed = text.trim()
      if (!trimmed || this.streaming) return
      const providersStore = useProvidersStore()
      const conversation = this.ensureConversation(providersStore.defaultRecord?.providerId ?? null)
      conversation.messages.push(
        newMessage({ role: 'user', content: trimmed, attachments: extra?.attachments ?? [] })
      )
      if (conversation.title === '新对话') {
        conversation.title = trimmed.slice(0, 18) || '新对话'
      }
      await this.runAssistant(conversation, trimmed, extra?.attachmentIds)
    },

    async retry() {
      const conversation = this.activeConversation
      if (!conversation || this.streaming) return
      const lastUser = [...conversation.messages].reverse().find((item) => item.role === 'user')
      if (!lastUser) return
      const last = conversation.messages[conversation.messages.length - 1]
      if (last && last.role === 'assistant' && last.status === 'error') {
        conversation.messages.pop()
      }
      await this.runAssistant(
        conversation,
        lastUser.content,
        lastUser.attachments.map((item) => item.id)
      )
    },

    async runAssistant(conversationArg: Conversation, text: string, attachmentIds?: number[]) {
      // Re-resolve the conversation through the reactive array. Pushing a raw object and then
      // mutating the local reference bypasses Vue reactivity, so streamed tokens would update the
      // store but never re-render the message bubble.
      const conversation =
        this.conversations.find((item) => item.id === conversationArg.id) ?? conversationArg
      conversation.messages.push(newMessage({ role: 'assistant', status: 'streaming' }))
      const assistant = conversation.messages[conversation.messages.length - 1]
      conversation.updatedAt = new Date().toISOString()
      this.streaming = true
      manualStop = false
      abortController = new AbortController()

      const request: ChatRequest = {
        conversationId: conversation.id,
        providerId: conversation.providerId ?? undefined,
        message: text,
        attachmentIds: attachmentIds && attachmentIds.length > 0 ? attachmentIds : undefined
      }

      try {
        await streamChat(
          request,
          {
            onToken: (token) => {
              assistant.content += token
            },
            onToolStart: (payload) => {
              assistant.toolCalls.push({
                id: randomId(),
                name: toolName(payload),
                status: 'running',
                payload,
                result: null
              })
            },
            onToolResult: (payload) => {
              const name = toolName(payload)
              const running = [...assistant.toolCalls]
                .reverse()
                .find((item) => item.name === name && item.status === 'running')
              if (running) {
                running.status = isFailedToolResult(payload) ? 'failed' : 'done'
                running.result = payload
              } else {
                assistant.toolCalls.push({
                  id: randomId(),
                  name,
                  status: 'done',
                  payload: null,
                  result: payload
                })
              }
            },
            onConfirmation: (payload) => {
              assistant.confirmation = toConfirmation(payload)
              const confirmation = assistant.confirmation
              if (confirmation.taskId) {
                void api
                  .getAgentTask(confirmation.taskId)
                  .then((task) => {
                    confirmation.task = task
                    confirmation.taskLoadError = null
                    this.persist()
                  })
                  .catch((error: unknown) => {
                    confirmation.taskLoadError =
                      error instanceof Error ? error.message : '任务详情加载失败'
                    this.persist()
                  })
              }
            },
            onError: (payload) => {
              assistant.error = stringifyValue(payload.message ?? payload.error ?? '生成过程中出现错误')
            },
            onDone: (payload) => {
              if (!assistant.content && typeof payload.content === 'string') {
                assistant.content = payload.content
              }
              assistant.providerId = typeof payload.providerId === 'string' ? payload.providerId : null
              assistant.modelName = typeof payload.modelName === 'string' ? payload.modelName : null
            }
          },
          abortController.signal
        )
        if (manualStop) assistant.stopped = true
        if (!assistant.content.trim() && !assistant.confirmation && !assistant.error && !manualStop) {
          assistant.error = '模型未返回可显示内容，请重试或切换模型'
        }
        assistant.status = assistant.error ? 'error' : 'done'
      } catch (error) {
        if (manualStop) {
          assistant.stopped = true
          assistant.status = 'done'
        } else {
          assistant.error = error instanceof Error ? error.message : '连接中断，请稍后重试'
          assistant.status = 'error'
        }
      } finally {
        for (const tool of assistant.toolCalls) {
          if (tool.status === 'running') tool.status = 'failed'
        }
        this.streaming = false
        abortController = null
        conversation.updatedAt = new Date().toISOString()
        this.persist()
      }
    },

    async resolveConfirmation(conversationId: string, message: ChatMessage, approved: boolean) {
      const confirmation = message.confirmation
      if (!confirmation || confirmation.resolving) return
      if (!confirmation.id) {
        confirmation.resultNote = '确认提案缺少 confirmationId，无法执行，请重新发起操作'
        confirmation.resultLevel = 'error'
        return
      }
      confirmation.resolving = true
      try {
        const result = await api.confirmChat({
          conversationId,
          confirmationId: confirmation.id,
          approved
        })
        confirmation.resolved = result.status !== 'PENDING'
        confirmation.approved = approved
        confirmation.resultNote = result.message
        confirmation.resultLevel = result.executed
          ? 'success'
          : result.status === 'REJECTED'
            ? 'info'
            : 'error'
        if (confirmation.taskId) {
          try {
            confirmation.task = await api.getAgentTask(confirmation.taskId)
            confirmation.taskLoadError = null
          } catch (taskError) {
            confirmation.taskLoadError =
              taskError instanceof Error ? taskError.message : '任务状态同步失败'
          }
        }
      } catch (error) {
        confirmation.resolved = false
        confirmation.resultNote = `确认请求发送失败：${
          error instanceof Error ? error.message : '未知错误'
        }（后端确认接口暂未开放，操作未执行）`
        confirmation.resultLevel = 'error'
      } finally {
        confirmation.resolving = false
        this.persist()
      }
    }
  }
})

function isFailedToolResult(payload: RecordValue): boolean {
  if (payload.ok === false || payload.success === false) return true
  const status = typeof payload.status === 'string' ? payload.status.toUpperCase() : ''
  return status === 'FAILED' || status === 'ERROR'
}
