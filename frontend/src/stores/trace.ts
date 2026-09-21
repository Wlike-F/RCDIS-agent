import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type { AgentTurnTraceVO } from '@/api/types'

interface TraceState {
  conversationId: string | null
  traces: AgentTurnTraceVO[]
  loading: boolean
  error: string | null
}

/**
 * Per-turn Agent observability traces of the active conversation (token usage, latency, tool chain).
 */
export const useTraceStore = defineStore('trace', {
  state: (): TraceState => ({
    conversationId: null,
    traces: [],
    loading: false,
    error: null
  }),
  getters: {
    totalTurns: (state) => state.traces.length,
    totalTokens: (state) => state.traces.reduce((acc, t) => acc + (t.totalTokens ?? 0), 0),
    totalToolCalls: (state) => state.traces.reduce((acc, t) => acc + (t.toolCalls?.length ?? 0), 0),
    avgTotalMs: (state) =>
      state.traces.length
        ? Math.round(state.traces.reduce((acc, t) => acc + (t.totalMs ?? 0), 0) / state.traces.length)
        : 0,
    maxTotalMs: (state) => state.traces.reduce((acc, t) => Math.max(acc, t.totalMs ?? 0), 0),
    maxTokens: (state) => state.traces.reduce((acc, t) => Math.max(acc, t.totalTokens ?? 0), 0)
  },
  actions: {
    async load(conversationId: string | null, force = false) {
      if (!conversationId) {
        this.conversationId = null
        this.traces = []
        return
      }
      if (!force && this.conversationId === conversationId && this.traces.length > 0) return
      this.loading = true
      this.error = null
      try {
        this.traces = await api.listChatTraces(conversationId)
        this.conversationId = conversationId
      } catch (error) {
        this.traces = []
        this.conversationId = conversationId
        this.error = toApiError(error).message
      } finally {
        this.loading = false
      }
    }
  }
})
