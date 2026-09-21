import { defineStore } from 'pinia'

import { api } from '@/api'
import type { AuthUser } from '@/api/types'
import {
  clearAuth,
  getAccessToken,
  getStoredUser,
  setAccessToken,
  setStoredUser
} from '@/utils/authToken'

interface AuthState {
  token: string | null
  user: AuthUser | null
}

/**
 * Holds the JWT and the logged-in user. The token is mirrored to localStorage so that the axios and
 * fetch interceptors can attach it without depending on this store (which itself calls the API).
 */
export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: getAccessToken(),
    user: getStoredUser()
  }),
  getters: {
    isAuthenticated: (state): boolean => !!state.token,
    roles: (state): string[] => state.user?.roles ?? [],
    // Backend maps AuthUserVO.username to the account display name.
    displayName: (state): string => state.user?.username ?? ''
  },
  actions: {
    async login(username: string, password: string): Promise<AuthUser> {
      const response = await api.login({ username, password })
      this.token = response.accessToken
      this.user = response.user
      setAccessToken(response.accessToken)
      setStoredUser(response.user)
      return response.user
    },
    logout() {
      this.token = null
      this.user = null
      clearAuth()
    },
    hasRole(role: string): boolean {
      return this.roles.includes(role)
    },
    hasAnyRole(...roles: string[]): boolean {
      return roles.some((role) => this.roles.includes(role))
    }
  }
})
