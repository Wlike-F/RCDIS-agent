import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type {
  DeleteRequest,
  ExpenseCreateRequest,
  ExpensePageRequest,
  ExpenseUpdateRequest,
  ExpenseVO
} from '@/api/types'

interface ExpensesState {
  expenses: ExpenseVO[]
  current: number
  size: number
  total: number
  pages: number
  loading: boolean
  error: string | null
}

export const useExpensesStore = defineStore('expenses', {
  state: (): ExpensesState => ({
    expenses: [],
    current: 1,
    size: 10,
    total: 0,
    pages: 0,
    loading: false,
    error: null
  }),
  actions: {
    async load(request: ExpensePageRequest) {
      this.loading = true
      this.error = null
      try {
        const page = await api.listExpenses(request)
        this.expenses = page.records
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

    async create(payload: ExpenseCreateRequest): Promise<ExpenseVO> {
      return api.createExpense(payload)
    },

    async update(id: number, payload: ExpenseUpdateRequest): Promise<ExpenseVO> {
      return api.updateExpense(id, payload)
    },

    async voidExpense(id: number, payload: DeleteRequest): Promise<void> {
      await api.deleteExpense(id, payload)
    }
  }
})
