import { createRouter, createWebHistory } from 'vue-router'

import MainLayout from '@/layouts/MainLayout.vue'
import { getAccessToken } from '@/utils/authToken'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录', public: true }
    },
    {
      path: '/',
      component: MainLayout,
      children: [
        {
          path: '',
          name: 'dashboard',
          component: () => import('@/views/DashboardView.vue'),
          meta: { title: '总览' }
        },
        {
          path: 'chat',
          name: 'chat',
          component: () => import('@/views/ChatView.vue'),
          meta: { title: '对话工作台' }
        },
        {
          path: 'projects',
          name: 'projects',
          component: () => import('@/views/ProjectsView.vue'),
          meta: { title: '科研项目' }
        },
        {
          path: 'reimbursements',
          name: 'reimbursements',
          component: () => import('@/views/ReimbursementsView.vue'),
          meta: { title: '报销中心' }
        },
        {
          path: 'providers',
          name: 'providers',
          component: () => import('@/views/ProvidersView.vue'),
          meta: { title: '模型供应商', roles: ['ADMIN'] }
        },
        {
          path: 'feishu',
          name: 'feishu',
          component: () => import('@/views/FeishuView.vue'),
          meta: { title: '飞书通知', roles: ['ADMIN'] }
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('@/views/UsersView.vue'),
          meta: { title: '用户管理', roles: ['ADMIN'] }
        },
        {
          path: 'developer',
          name: 'developer',
          component: () => import('@/views/DeveloperView.vue'),
          meta: { title: '开发者管理', roles: ['ADMIN'] }
        },
        {
          path: 'observability',
          name: 'observability',
          component: () => import('@/views/AgentObservabilityView.vue'),
          meta: { title: 'Agent 运行观测', roles: ['ADMIN'] }
        }
      ]
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

// Read the token straight from storage so the guard does not depend on Pinia initialization order.
router.beforeEach((to) => {
  const authenticated = !!getAccessToken()
  if (!to.meta.public && !authenticated) {
    return { name: 'login', query: to.fullPath === '/' ? {} : { redirect: to.fullPath } }
  }
  if (to.name === 'login' && authenticated) {
    return { path: '/' }
  }
  return true
})

router.afterEach((to) => {
  const title = to.meta.title as string | undefined
  document.title = title ? `${title} · RCDIS Agent` : 'RCDIS Agent'
})

export default router
