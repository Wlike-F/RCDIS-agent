import { API_BASE_URL } from './client'
import { getAccessToken } from '@/utils/authToken'
import type { ChatRequest, RecordValue } from './types'

export interface ChatStreamHandlers {
  onStart?: () => void
  onToken?: (text: string) => void
  onToolStart?: (payload: RecordValue) => void
  onToolResult?: (payload: RecordValue) => void
  onConfirmation?: (payload: RecordValue) => void
  onError?: (payload: RecordValue) => void
  onDone?: (payload: RecordValue) => void
}

interface SseBlock {
  event: string
  data: string
}

function parseBlocks(buffer: string): { blocks: SseBlock[]; rest: string } {
  const blocks: SseBlock[] = []
  const parts = buffer.split(/\r?\n\r?\n/)
  const rest = parts.pop() ?? ''
  for (const part of parts) {
    let event = 'message'
    const dataLines: string[] = []
    for (const line of part.split(/\r?\n/)) {
      if (line.startsWith('event:')) {
        event = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }
    if (dataLines.length > 0 || event !== 'message') {
      blocks.push({ event, data: dataLines.join('\n') })
    }
  }
  return { blocks, rest }
}

function safeJson(raw: string): RecordValue | string {
  try {
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? (parsed as RecordValue) : raw
  } catch {
    return raw
  }
}

function asRecord(payload: RecordValue | string): RecordValue {
  return typeof payload === 'string' ? { message: payload } : payload
}

function extractTokenText(payload: RecordValue | string): string {
  if (typeof payload === 'string') return payload
  const text = payload.text
  return typeof text === 'string' ? text : ''
}

/**
 * Post-based SSE consumer: the chat stream endpoint is a POST, so the native
 * EventSource API cannot be used. Events follow the backend contract:
 * start / token / tool_start / tool_result / requires_confirmation / error / done.
 */
export async function streamChat(
  request: ChatRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal
): Promise<void> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  const token = getAccessToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(`${API_BASE_URL}/api/chat/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(request),
    signal
  })

  if (!response.ok || !response.body) {
    throw new Error(`连接失败（HTTP ${response.status}），请确认后端服务已启动`)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let terminalEventReceived = false

  const dispatch = (block: SseBlock) => {
    if (!block.data) return
    const payload = safeJson(block.data)
    switch (block.event) {
      case 'start':
        handlers.onStart?.()
        break
      case 'token': {
        const text = extractTokenText(payload)
        if (text) handlers.onToken?.(text)
        break
      }
      case 'tool_start':
        handlers.onToolStart?.(asRecord(payload))
        break
      case 'tool_result':
        handlers.onToolResult?.(asRecord(payload))
        break
      case 'requires_confirmation':
        handlers.onConfirmation?.(asRecord(payload))
        break
      case 'notification_sent':
        handlers.onToolResult?.(asRecord(payload))
        break
      case 'error':
        terminalEventReceived = true
        handlers.onError?.(asRecord(payload))
        break
      case 'done':
        terminalEventReceived = true
        handlers.onDone?.(asRecord(payload))
        break
      default:
        break
    }
  }

  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const { blocks, rest } = parseBlocks(buffer)
      buffer = rest
      for (const block of blocks) dispatch(block)
    }
    if (buffer.trim()) {
      const { blocks } = parseBlocks(`${buffer}\n\n`)
      for (const block of blocks) dispatch(block)
    }
    if (!terminalEventReceived) {
      throw new Error('对话流在返回完成事件前已中断，请重试')
    }
  } finally {
    reader.releaseLock()
  }
}
