import { defineStore } from 'pinia'

import { api } from '@/api'
import { openNotificationStream } from '@/api/notificationStream'
import type { AppNotificationVO } from '@/api/types'

/** Low-frequency REST reconcile; the SSE stream is the primary delivery path. */
const RECONCILE_INTERVAL_MS = 5 * 60_000
const RECONNECT_BASE_DELAY_MS = 1_000
const RECONNECT_MAX_DELAY_MS = 30_000
const PAGE_SIZE = 20

interface NotificationState {
  items: AppNotificationVO[]
  total: number
  unread: number
  loading: boolean
  connected: boolean
}

/**
 * Holds the in-app notification list and unread count.
 *
 * Delivery is SSE-push first: while the user stays logged in a long-lived stream feeds every new
 * notification in real time, and the reconnect loop (exponential backoff) covers network blips and
 * backend restarts. Because the server commits each row before pushing, a reconnect only needs to
 * resync the unread count — nothing is lost. The 5-minute reconcile timer guards against a silently
 * dropped stream, and the list itself is only fetched when the bell popover is open.
 */
export const useNotificationStore = defineStore('notification', {
  state: (): NotificationState => ({
    items: [],
    total: 0,
    unread: 0,
    loading: false,
    connected: false
  }),
  actions: {
    async fetchUnread() {
      try {
        this.unread = await api.countUnreadNotifications()
      } catch {
        // Reconcile failures (backend restart, network blip) are silent; the next round retries.
      }
    },
    async fetchList() {
      this.loading = true
      try {
        const page = await api.listMyNotifications(1, PAGE_SIZE)
        this.items = page.records
        this.total = page.total
      } finally {
        this.loading = false
      }
    },
    async markRead(id: number) {
      const item = this.items.find((entry) => entry.id === id)
      if (!item || item.read) return
      await api.markNotificationRead(id)
      item.read = true
      this.unread = Math.max(0, this.unread - 1)
    },
    async markAllRead() {
      await api.markAllNotificationsRead()
      this.items.forEach((item) => {
        item.read = true
      })
      this.unread = 0
    },
    startStream() {
      if (streamController) return
      streamController = new AbortController()
      const signal = streamController.signal
      reconcileTimer = setInterval(() => {
        void this.fetchUnread()
      }, RECONCILE_INTERVAL_MS)
      void this.runStreamLoop(signal)
    },
    stopStream() {
      streamController?.abort()
      streamController = null
      if (reconcileTimer) {
        clearInterval(reconcileTimer)
        reconcileTimer = null
      }
      this.connected = false
      this.unread = 0
      this.items = []
      this.total = 0
    },
    async runStreamLoop(signal: AbortSignal) {
      let attempt = 0
      while (!signal.aborted) {
        try {
          await this.runOneStream(signal, () => {
            // The stream proved healthy once; start the next outage at the fast retry rung.
            attempt = 0
          })
        } catch {
          // Connect failure or mid-stream network drop; the backoff below retries.
        }
        if (signal.aborted) break
        // Reconnect with exponential backoff; attempt resets once the stream reports healthy.
        const delay = Math.min(RECONNECT_BASE_DELAY_MS * 2 ** attempt, RECONNECT_MAX_DELAY_MS)
        attempt = Math.min(attempt + 1, 5)
        await sleep(delay, signal)
      }
      this.connected = false
    },
    /** Resolves when the server closes the stream (backend restart, emitter completed). */
    async runOneStream(signal: AbortSignal, onUp: () => void): Promise<void> {
      try {
        await openNotificationStream(
          {
            onHello: () => {
              this.connected = true
              onUp()
              // Reconcile counters on (re)connect; rows committed while offline show up here.
              void this.fetchUnread()
              if (this.items.length > 0) void this.fetchList()
            },
            onNotification: (notification) => {
              if (this.items.some((entry) => entry.id === notification.id)) return
              this.unread += 1
              // Only keep the list in sync while the popover has it loaded; otherwise the bell
              // badge is enough and fetchList() refreshes everything when opened.
              if (this.items.length > 0) {
                this.items = [notification, ...this.items].slice(0, PAGE_SIZE)
                this.total += 1
              }
            }
          },
          signal
        )
      } finally {
        this.connected = false
      }
    }
  }
})

// Non-reactive lifecycle handles live outside state: Pinia proxies state objects, which would
// break AbortController internals and leak timers into devtools serialization.
let streamController: AbortController | null = null
let reconcileTimer: ReturnType<typeof setInterval> | null = null

function sleep(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms)
    signal.addEventListener('abort', () => {
      clearTimeout(timer)
      resolve()
    }, { once: true })
  })
}
