import type { AuthUser } from '@/api/types'

// Auth state lives in localStorage so that the axios/fetch interceptors can read the token without
// importing the Pinia store (which itself calls the API), avoiding a circular dependency.
const TOKEN_KEY = 'rcdis.auth.token'
const USER_KEY = 'rcdis.auth.user'

export function getAccessToken(): string | null {
  try {
    return window.localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function setAccessToken(token: string): void {
  try {
    window.localStorage.setItem(TOKEN_KEY, token)
  } catch {
    // Storage may be unavailable in private mode; persistence is best effort.
  }
}

export function getStoredUser(): AuthUser | null {
  try {
    const raw = window.localStorage.getItem(USER_KEY)
    return raw ? (JSON.parse(raw) as AuthUser) : null
  } catch {
    return null
  }
}

export function setStoredUser(user: AuthUser): void {
  try {
    window.localStorage.setItem(USER_KEY, JSON.stringify(user))
  } catch {
    // best effort
  }
}

export function clearAuth(): void {
  try {
    window.localStorage.removeItem(TOKEN_KEY)
    window.localStorage.removeItem(USER_KEY)
  } catch {
    // best effort
  }
}
