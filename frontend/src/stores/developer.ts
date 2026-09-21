import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type { AgentToolVO } from '@/api/types'

interface DeveloperState {
  tools: AgentToolVO[]
  loading: boolean
  loaded: boolean
  error: string | null
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
    error: null
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
    }
  }
})
