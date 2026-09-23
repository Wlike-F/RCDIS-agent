import { API_BASE_URL } from './client'
import { getAccessToken } from '@/utils/authToken'
import { readSseStream, safeJsonParse } from '@/utils/sse'
import type { SseBlock } from '@/utils/sse'
import type { AppNotificationVO } from './types'

export interface NotificationStreamHandlers {
  /** Stream opened (or re-opened); the caller should reconcile state right away. */
  onHello?: () => void
  /** One new notification for the connected user. */
  onNotification?: (notification: AppNotificationVO) => void
}

/**
 * Opens the notification SSE stream and keeps reading until the connection drops or the signal
 * aborts. Uses fetch instead of EventSource because the endpoint requires the Authorization header.
 *
 * Resolves normally when the server closes the stream (backend restart, emitter completed); the
 * caller's reconnect loop decides whether to retry. Rejects only on connect/network failures.
 */
export async function openNotificationStream(
  handlers: NotificationStreamHandlers,
  signal: AbortSignal
): Promise<void> {
  const headers: Record<string, string> = { Accept: 'text/event-stream' }
  const token = getAccessToken()
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const response = await fetch(`${API_BASE_URL}/api/notifications/stream`, {
    method: 'GET',
    headers,
    signal
  })
  if (!response.ok || !response.body) {
    throw new Error(`通知流连接失败（HTTP ${response.status}）`)
  }

  const dispatch = (block: SseBlock) => {
    if (!block.data) return
    switch (block.event) {
      case 'hello':
        handlers.onHello?.()
        break
      case 'notification': {
        const payload = safeJsonParse(block.data)
        if (payload && typeof payload === 'object') {
          handlers.onNotification?.(payload as AppNotificationVO)
        }
        break
      }
      default:
        break
    }
  }

  await readSseStream(response.body, dispatch)
}
