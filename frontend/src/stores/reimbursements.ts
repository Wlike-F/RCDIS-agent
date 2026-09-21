import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type {
  MaterialCheckVO,
  ReimbursementActionRequest,
  ReimbursementCreateRequest,
  ReimbursementDetailVO,
  ReimbursementPageRequest,
  ReimbursementUpdateRequest,
  ReimbursementVO
} from '@/api/types'

export type ReimbursementStatus = 'draft' | 'submitted' | 'approved' | 'rejected' | 'void'

export type { MaterialCheckVO as MaterialCheckResult } from '@/api/types'

interface ReimbursementsState {
  records: ReimbursementVO[]
  current: number
  size: number
  total: number
  pages: number
  loading: boolean
  error: string | null
}

export const useReimbursementsStore = defineStore('reimbursements', {
  state: (): ReimbursementsState => ({
    records: [],
    current: 1,
    size: 10,
    total: 0,
    pages: 0,
    loading: false,
    error: null
  }),
  actions: {
    async load(request: ReimbursementPageRequest) {
      this.loading = true
      this.error = null
      try {
        const page = await api.listReimbursements(request)
        this.records = page.records
        this.current = page.current
        this.size = page.size
        this.total = page.total
        this.pages = page.pages
      } catch (error) {
        this.error = toApiError(error).message
      } finally {
        this.loading = false
      }
    },

    async fetchDetail(id: number): Promise<ReimbursementDetailVO> {
      return api.getReimbursement(id)
    },

    async create(payload: ReimbursementCreateRequest): Promise<ReimbursementDetailVO> {
      return api.createReimbursement(payload)
    },

    async update(
      id: number,
      payload: ReimbursementUpdateRequest
    ): Promise<ReimbursementDetailVO> {
      return api.updateReimbursement(id, payload)
    },

    async voidOrder(id: number, payload: ReimbursementActionRequest): Promise<ReimbursementDetailVO> {
      return api.voidReimbursement(id, payload)
    },

    async checkMaterials(id: number): Promise<MaterialCheckVO> {
      return api.checkReimbursementMaterials(id)
    },

    async submit(id: number, payload: ReimbursementActionRequest): Promise<ReimbursementDetailVO> {
      return api.submitReimbursement(id, payload)
    },

    async withdraw(id: number, payload: ReimbursementActionRequest): Promise<ReimbursementDetailVO> {
      return api.withdrawReimbursement(id, payload)
    },

    async approve(
      id: number,
      payload: ReimbursementActionRequest
    ): Promise<ReimbursementDetailVO> {
      return api.approveReimbursement(id, payload)
    },

    async reject(
      id: number,
      payload: ReimbursementActionRequest
    ): Promise<ReimbursementDetailVO> {
      return api.rejectReimbursement(id, payload)
    }
  }
})
