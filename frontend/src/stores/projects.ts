import { defineStore } from 'pinia'

import { api } from '@/api'
import type { ProjectPageRequest, ProjectVO } from '@/api/types'
import { sumMoney } from '@/utils/money'

interface ProjectsState {
  projects: ProjectVO[]
  current: number
  size: number
  total: number
  pages: number
  loading: boolean
  loaded: boolean
  error: string | null
}

function resolveProjectPageRequest(
  request: ProjectPageRequest | undefined,
  current: number,
  size: number
): ProjectPageRequest {
  return {
    current: request?.current ?? current,
    size: request?.size ?? size,
    keyword: request?.keyword,
    status: request?.status
  }
}

export const useProjectsStore = defineStore('projects', {
  state: (): ProjectsState => ({
    projects: [],
    current: 1,
    size: 20,
    total: 0,
    pages: 0,
    loading: false,
    loaded: false,
    error: null
  }),
  getters: {
    totalCount: (state) => state.total,
    totalBudget: (state) => sumMoney(state.projects.map((project) => project.totalBudget))
  },
  actions: {
    async load(force = false, request?: ProjectPageRequest) {
      if (this.loading) return
      if (this.loaded && !force) return
      this.loading = true
      this.error = null
      try {
        const pageRequest = resolveProjectPageRequest(request, this.current, this.size)
        const page = await api.listProjects(pageRequest)
        this.projects = page.records
        this.current = page.current
        this.size = page.size
        this.total = page.total
        this.pages = page.pages
        this.loaded = true
      } catch (error) {
        this.error = error instanceof Error ? error.message : '加载科研项目失败'
      } finally {
        this.loading = false
      }
    }
  }
})
