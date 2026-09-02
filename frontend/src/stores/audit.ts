import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type { AuditLogVO } from '@/api/types'

interface AuditState {
  entries: AuditLogVO[]
  loading: boolean
  error: string | null
}

export const useAuditStore = defineStore('audit', {
  state: (): AuditState => ({
    entries: [],
    loading: false,
    error: null
  }),
  actions: {
    async load() {
      if (this.loading) return
      this.loading = true
      this.error = null
      try {
        const page = await api.listAuditLogs({ current: 1, size: 100 })
        this.entries = page.records
      } catch (error) {
        this.error = toApiError(error).message
      } finally {
        this.loading = false
      }
    }
  }
})
