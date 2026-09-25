import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type { AgentToolVO, MemoryRetrievalProbeVO } from '@/api/types'

interface DeveloperState {
  tools: AgentToolVO[]
  loading: boolean
  loaded: boolean
  error: string | null
  backfilling: boolean
  probing: boolean
  probeError: string | null
  probeResult: MemoryRetrievalProbeVO | null
}

/**
 * Developer console data. Read-only for now (Agent tool catalogue); more diagnostics hang off this
 * store as the console grows.
 */
export const useDeveloperStore = defineStore('developer', {
  state: (): DeveloperState => ({
    tools: [],
    loading: false,
    loaded: false,
    error: null,
    backfilling: false,
    probing: false,
    probeError: null,
    probeResult: null
  }),
  getters: {
    readTools: (state) => state.tools.filter((tool) => tool.category === 'READ'),
    writeTools: (state) => state.tools.filter((tool) => tool.category === 'WRITE')
  },
  actions: {
    async load(force = false) {
      if (this.loading) return
      if (this.loaded && !force) return
      this.loading = true
      this.error = null
      try {
        this.tools = await api.listAgentTools()
        this.loaded = true
      } catch (error) {
        this.error = toApiError(error).message
      } finally {
        this.loading = false
      }
    },
    /**
     * Triggers a bounded pgvector embedding backfill for semantic memories that lack one.
     * No-ops on the backend when semantic-retrieval is disabled.
     *
     * @returns the number of rows successfully embedded
     */
    async backfillEmbeddings(limit: number): Promise<number> {
      if (this.backfilling) return 0
      this.backfilling = true
      try {
        const result = await api.backfillMemoryEmbeddings(limit)
        return result.embedded
      } finally {
        this.backfilling = false
      }
    },
    /**
     * Runs a read-only semantic-memory retrieval probe and stores the result for display.
     * Failures are surfaced via {@code probeError} rather than thrown to the caller.
     */
    async runProbe(params: { userId?: string; query: string; topK?: number }): Promise<void> {
      if (this.probing) return
      this.probing = true
      this.probeError = null
      try {
        this.probeResult = await api.memoryRetrievalProbe(params)
      } catch (error) {
        this.probeError = toApiError(error).message
      } finally {
        this.probing = false
      }
    }
  }
})
