import { defineStore } from 'pinia'

import { api } from '@/api'
import type { HealthVO } from '@/api/types'
import { loadJSON, saveJSON } from '@/utils/storage'

export type HealthState = 'unknown' | 'checking' | 'up' | 'down'

interface AppState {
  sidebarCollapsed: boolean
  health: HealthVO | null
  healthState: HealthState
}

export const useAppStore = defineStore('app', {
  state: (): AppState => ({
    sidebarCollapsed: loadJSON<boolean>('rcdis.sidebar.collapsed', false),
    health: null,
    healthState: 'unknown'
  }),
  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
      saveJSON('rcdis.sidebar.collapsed', this.sidebarCollapsed)
    },
    async refreshHealth() {
      this.healthState = 'checking'
      try {
        this.health = await api.health()
        this.healthState = this.health.status === 'UP' ? 'up' : 'down'
      } catch {
        this.healthState = 'down'
      }
    }
  }
})
