import { defineStore } from 'pinia'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import type {
  UserCreateRequest,
  UserPageRequest,
  UserPasswordRequest,
  UserRolesRequest,
  UserVO
} from '@/api/types'

interface UsersState {
  records: UserVO[]
  total: number
  current: number
  size: number
  keyword: string
  loading: boolean
  loaded: boolean
  error: string | null
  busyId: number | null
}

/**
 * User administration state. Accounts and roles live in PostgreSQL; nothing is cached client-side
 * beyond the current page. Every mutation reloads so status/version stay consistent with the backend.
 */
export const useUsersStore = defineStore('users', {
  state: (): UsersState => ({
    records: [],
    total: 0,
    current: 1,
    size: 10,
    keyword: '',
    loading: false,
    loaded: false,
    error: null,
    busyId: null
  }),
  actions: {
    async load(force = false) {
      if (this.loading) return
      if (this.loaded && !force) return
      this.loading = true
      this.error = null
      try {
        const request: UserPageRequest = {
          current: this.current,
          size: this.size,
          keyword: this.keyword || undefined
        }
        const page = await api.listUsers(request)
        this.records = page.records
        this.total = page.total
        this.loaded = true
      } catch (error) {
        this.error = toApiError(error).message
      } finally {
        this.loading = false
      }
    },

    async search(keyword: string) {
      this.keyword = keyword
      this.current = 1
      await this.load(true)
    },

    async changePage(current: number) {
      this.current = current
      await this.load(true)
    },

    async create(payload: UserCreateRequest): Promise<UserVO> {
      const created = await api.createUser(payload)
      await this.load(true)
      return created
    },

    async updatePassword(id: number, payload: UserPasswordRequest): Promise<void> {
      await this.runExclusive(id, async () => {
        await api.updateUserPassword(id, payload)
      })
    },

    async toggleStatus(id: number): Promise<UserVO | null> {
      return this.runExclusive(id, () => api.toggleUserStatus(id))
    },

    async assignRoles(id: number, payload: UserRolesRequest): Promise<UserVO | null> {
      return this.runExclusive(id, () => api.assignUserRoles(id, payload))
    },

    /** Marks one row busy, runs the call, then reloads the current page. */
    async runExclusive<T>(id: number, task: () => Promise<T>): Promise<T | null> {
      this.busyId = id
      try {
        const result = await task()
        await this.load(true)
        return result
      } finally {
        if (this.busyId === id) {
          this.busyId = null
        }
      }
    }
  }
})
