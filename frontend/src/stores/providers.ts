import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type {
  ModelProtocolVO,
  ModelProviderCreateRequest,
  ModelProviderDiscoveryVO,
  ModelProviderTestRequest,
  ModelProviderTestResponse,
  ModelProviderUpdateRequest,
  ModelProviderVO
} from '@/api/types'

export type ProviderBusyAction = 'test' | 'discover' | 'save' | 'remove'

interface ProvidersState {
  records: ModelProviderVO[]
  protocols: ModelProtocolVO[]
  loading: boolean
  loaded: boolean
  error: string | null
  busyId: number | null
  busyAction: ProviderBusyAction | null
}

/**
 * Model providers are persisted in PostgreSQL and managed through the API.
 *
 * Earlier revisions kept user-added providers in localStorage and held API keys in memory only;
 * both are gone, so nothing here survives a reload except what the backend returns.
 */
export const useProvidersStore = defineStore('providers', {
  state: (): ProvidersState => ({
    records: [],
    protocols: [],
    loading: false,
    loaded: false,
    error: null,
    busyId: null,
    busyAction: null
  }),
  getters: {
    enabledRecords: (state) => state.records.filter((record) => record.enabled),
    defaultRecord: (state) =>
      state.records.find((record) => record.defaultProvider) ??
      state.records.find((record) => record.enabled) ??
      null,
    supportedProtocols: (state) => state.protocols.filter((protocol) => protocol.supported)
  },
  actions: {
    isBusy(id: number | null, action?: ProviderBusyAction): boolean {
      if (id == null || this.busyId !== id) return false
      return action == null || this.busyAction === action
    },

    async load(force = false) {
      if (this.loading) return
      if (this.loaded && !force) return
      this.loading = true
      this.error = null
      try {
        this.records = await api.listProviders()
        this.loaded = true
      } catch (error) {
        this.error = toApiError(error).message
      } finally {
        this.loading = false
      }
    },

    async loadProtocols() {
      if (this.protocols.length > 0) return
      try {
        this.protocols = await api.listModelProtocols()
      } catch (error) {
        // The catalogue only enriches the form, so a failure must not block provider management.
        this.protocols = []
        this.error = toApiError(error).message
      }
    },

    async create(payload: ModelProviderCreateRequest): Promise<ModelProviderVO> {
      const created = await api.createProvider(payload)
      await this.load(true)
      return created
    },

    async update(id: number, payload: ModelProviderUpdateRequest): Promise<ModelProviderVO> {
      const updated = await api.updateProvider(id, payload)
      await this.load(true)
      return updated
    },

    async remove(id: number, reason: string, version: number): Promise<void> {
      await this.runExclusive(id, 'remove', async () => {
        await api.deleteProvider(id, { reason, version })
      })
    },

    async toggleStatus(id: number): Promise<ModelProviderVO | null> {
      return this.runExclusive(id, 'save', () => api.toggleProviderStatus(id))
    },

    async setDefault(id: number): Promise<ModelProviderVO | null> {
      return this.runExclusive(id, 'save', () => api.setDefaultProvider(id))
    },

    async setDefaultModel(id: number, modelId: number): Promise<ModelProviderVO | null> {
      return this.runExclusive(id, 'save', () => api.setDefaultProviderModel(id, modelId))
    },

    async discoverModels(id: number, persist: boolean): Promise<ModelProviderDiscoveryVO | null> {
      return this.runExclusive(id, 'discover', () => api.discoverProviderModels(id, { persist }))
    },

    async test(payload: ModelProviderTestRequest): Promise<ModelProviderTestResponse | null> {
      const record = payload.providerId
        ? this.records.find((item) => item.providerId === payload.providerId)
        : this.defaultRecord
      return this.runExclusive(record?.id ?? null, 'test', () => api.testProvider(payload))
    },

    /**
     * Marks one provider busy, runs the call, then reloads so that persisted fields such as
     * lastTest and version stay consistent with the backend.
     */
    async runExclusive<T>(
      id: number | null,
      action: ProviderBusyAction,
      task: () => Promise<T>
    ): Promise<T | null> {
      if (id != null) {
        this.busyId = id
        this.busyAction = action
      }
      try {
        const result = await task()
        await this.load(true)
        return result
      } finally {
        if (id != null && this.busyId === id) {
          this.busyId = null
          this.busyAction = null
        }
      }
    }
  }
})
