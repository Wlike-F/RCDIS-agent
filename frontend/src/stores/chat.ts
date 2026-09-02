import { defineStore } from 'pinia'

import { api } from '@/api'
import { streamChat } from '@/api/chat'
import type { ChatRequest, RecordValue } from '@/api/types'
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
}

export interface ChatMessage {
  id: string
  role: MessageRole
  content: string
  time: string
  status: MessageStatus
  toolCalls: ToolCall[]
  confirmation: Confirmation | null
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

const STORAGE_KEY = 'rcdis.chat.conversations.v1'

interface ChatState {
  conversations: Conversation[]
  activeId: string | null
  streaming: boolean
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
  return {
    id: typeof payload.confirmationId === 'string' ? payload.confirmationId : null,
    operation: stringifyValue(payload.operation ?? payload.action ?? payload.type),
    targetType: stringifyValue(payload.targetType ?? payload.target_type),
    targetId: stringifyValue(payload.targetId ?? payload.target_id),
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
    resultLevel: null
  }
}

export const useChatStore = defineStore('chat', {
  state: (): ChatState => {
    const persisted = loadJSON<Conversation[]>(STORAGE_KEY, [])
    return {
      conversations: Array.isArray(persisted) ? persisted : [],
      activeId: null,
      streaming: false
    }
  },
  getters: {
    activeConversation(state): Conversation | null {
      return state.conversations.find((item) => item.id === state.activeId) ?? null
    },
    isEmpty: (state) => state.conversations.length === 0
  },
  actions: {
    persist() {
      saveJSON(STORAGE_KEY, this.conversations)
    },

    initActive() {
      if (this.activeId && this.conversations.some((item) => item.id === this.activeId)) return
      if (this.conversations.length > 0) {
        this.activeId = this.conversations[0].id
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
    },

    removeConversation(id: string) {
      const index = this.conversations.findIndex((item) => item.id === id)
      if (index < 0) return
      this.conversations.splice(index, 1)
      if (this.activeId === id) {
        this.activeId = this.conversations[0]?.id ?? null
      }
      this.persist()
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

    async send(text: string) {
      const trimmed = text.trim()
      if (!trimmed || this.streaming) return
      const providersStore = useProvidersStore()
      const conversation = this.ensureConversation(providersStore.defaultRecord?.providerId ?? null)
      conversation.messages.push(newMessage({ role: 'user', content: trimmed }))
      if (conversation.title === '新对话') {
        conversation.title = trimmed.slice(0, 18) || '新对话'
      }
      await this.runAssistant(conversation, trimmed)
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
      await this.runAssistant(conversation, lastUser.content)
    },

    async runAssistant(conversation: Conversation, text: string) {
      const assistant = newMessage({ role: 'assistant', status: 'streaming' })
      conversation.messages.push(assistant)
      conversation.updatedAt = new Date().toISOString()
      this.streaming = true
      manualStop = false
      abortController = new AbortController()

      const request: ChatRequest = {
        conversationId: conversation.id,
        providerId: conversation.providerId ?? undefined,
        message: text
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
                running.status = 'done'
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
        this.streaming = false
        abortController = null
        conversation.updatedAt = new Date().toISOString()
        this.persist()
      }
    },

    async resolveConfirmation(message: ChatMessage, approved: boolean) {
      const confirmation = message.confirmation
      if (!confirmation || confirmation.resolving) return
      confirmation.resolving = true
      try {
        await api.confirmChat({
          conversationId: this.activeId ?? '',
          confirmationId: confirmation.id ?? undefined,
          approved
        })
        confirmation.resolved = true
        confirmation.approved = approved
        confirmation.resultNote = approved
          ? '确认已提交，操作已进入执行流程'
          : '已取消，本次操作不会执行'
        confirmation.resultLevel = approved ? 'success' : 'info'
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
