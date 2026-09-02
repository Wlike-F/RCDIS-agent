<template>
  <div class="layout">
    <aside class="sidebar" :class="{ collapsed: appStore.sidebarCollapsed }">
      <div class="brand">
        <div class="brand-mark">R</div>
        <transition name="fade">
          <div v-if="!appStore.sidebarCollapsed" class="brand-text">
            <span class="brand-name">RCDIS Agent</span>
            <span class="brand-sub">实验室经费管理智能体</span>
          </div>
        </transition>
      </div>

      <el-scrollbar class="nav-scroll">
        <el-menu
          class="rc-side-menu"
          :collapse="appStore.sidebarCollapsed"
          :default-active="activePath"
          router
        >
          <template v-for="section in sections" :key="section.label">
            <div v-if="!appStore.sidebarCollapsed" class="menu-group-label">{{ section.label }}</div>
            <el-menu-item v-for="item in section.items" :key="item.path" :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <template #title>{{ item.label }}</template>
            </el-menu-item>
          </template>
        </el-menu>
      </el-scrollbar>

      <div class="sidebar-footer">
        <button class="collapse-btn" type="button" @click="appStore.toggleSidebar()">
          <el-icon :size="16">
            <Fold v-if="!appStore.sidebarCollapsed" />
            <Expand v-else />
          </el-icon>
          <span v-if="!appStore.sidebarCollapsed">收起导航</span>
        </button>
        <transition name="fade">
          <span v-if="!appStore.sidebarCollapsed" class="version num">v0.1.0 · prototype</span>
        </transition>
      </div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <div class="topbar-left">
          <span class="kicker">{{ sectionLabel }}</span>
          <span class="topbar-divider"></span>
          <span class="topbar-title">{{ currentTitle }}</span>
        </div>
        <div class="topbar-right">
          <el-tooltip content="点击重新检测后端服务连接状态" placement="bottom">
            <button class="svc-chip" type="button" :class="appStore.healthState" @click="refreshHealth">
              <span class="svc-dot"></span>
              <span>{{ healthLabel }}</span>
            </button>
          </el-tooltip>
          <span class="topbar-divider"></span>
          <div class="user-chip">
            <div class="user-avatar">管</div>
            <div class="user-meta">
              <span class="user-name">管理员</span>
              <span class="user-role">实验室负责人</span>
            </div>
          </div>
        </div>
      </header>

      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="page" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'

import { useAppStore } from '@/stores/app'

interface NavItem {
  path: string
  label: string
  icon: string
}

interface NavSection {
  label: string
  en: string
  items: NavItem[]
}

const route = useRoute()
const appStore = useAppStore()

const sections: NavSection[] = [
  { label: '概览', en: 'OVERVIEW', items: [{ path: '/', label: '总览', icon: 'Odometer' }] },
  { label: '智能体', en: 'AGENT', items: [{ path: '/chat', label: '对话工作台', icon: 'ChatDotRound' }] },
  {
    label: '业务管理',
    en: 'OPERATIONS',
    items: [
      { path: '/projects', label: '科研项目', icon: 'Folder' },
      { path: '/expenses', label: '支出管理', icon: 'Money' },
      { path: '/reimbursements', label: '报销中心', icon: 'Tickets' }
    ]
  },
  {
    label: '系统设置',
    en: 'SETTINGS',
    items: [
      { path: '/providers', label: '模型供应商', icon: 'Cpu' },
      { path: '/feishu', label: '飞书通知', icon: 'Bell' }
    ]
  }
]

const activePath = computed(() => route.path)
const currentTitle = computed(() => (route.meta.title as string) ?? '')

const sectionLabel = computed(() => {
  for (const section of sections) {
    if (section.items.some((item) => item.path === route.path)) {
      return section.en
    }
  }
  return 'RCDIS AGENT'
})

const healthLabel = computed(() => {
  switch (appStore.healthState) {
    case 'up':
      return '服务正常'
    case 'down':
      return '服务离线'
    case 'checking':
      return '检测中'
    default:
      return '未检测'
  }
})

function refreshHealth() {
  void appStore.refreshHealth()
}

onMounted(() => {
  void appStore.refreshHealth()
  if (window.innerWidth < 1200 && !appStore.sidebarCollapsed) {
    appStore.toggleSidebar()
  }
})
</script>

<style scoped lang="scss">
.layout {
  display: flex;
  height: 100%;
  overflow: hidden;
}

// ---------- Sidebar ----------
.sidebar {
  display: flex;
  flex-direction: column;
  width: var(--rc-sidebar-width);
  background: var(--rc-ink);
  border-right: 1px solid var(--rc-line);
  transition: width 0.2s ease;
  flex-shrink: 0;

  &.collapsed {
    width: var(--rc-sidebar-collapsed);
  }
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px 18px;
  border-bottom: 1px solid var(--rc-ink-line);
  min-height: 72px;
}

.brand-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: linear-gradient(135deg, #2f54eb, #5e7bf7);
  color: #ffffff;
  font-family: Georgia, 'Times New Roman', serif;
  font-size: 18px;
  font-weight: 700;
  box-shadow: 0 6px 16px rgba(47, 84, 235, 0.4);
  flex-shrink: 0;
}

.brand-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.brand-name {
  color: var(--rc-ink-title);
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.02em;
  line-height: 1.25;
  white-space: nowrap;
}

.brand-sub {
  margin-top: 3px;
  color: var(--rc-ink-text-dim);
  font-size: 11px;
  letter-spacing: 0.08em;
  white-space: nowrap;
}

.nav-scroll {
  flex: 1;
}

.menu-item-text {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.menu-soon {
  transform: scale(0.86);
}

.sidebar-footer {
  padding: 12px 14px 14px;
  border-top: 1px solid var(--rc-ink-line);
}

.collapse-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--rc-ink-text);
  font-size: 12px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.15s ease, color 0.15s ease;

  &:hover {
    background: #f2f4fa;
    color: #1c2333;
  }
}

.version {
  display: block;
  margin-top: 8px;
  padding-left: 10px;
  font-size: 10px;
  color: var(--rc-ink-text-dim);
}

// ---------- Workspace ----------
.workspace {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: var(--rc-topbar-height);
  padding: 0 24px;
  background: rgba(255, 255, 255, 0.92);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--rc-line);
  flex-shrink: 0;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.topbar-divider {
  width: 1px;
  height: 16px;
  background: var(--rc-line-strong);
}

.topbar-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--rc-text);
}

.topbar-right {
  display: flex;
  align-items: center;
  gap: 14px;
}

.svc-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 12px;
  border: 1px solid var(--rc-line);
  border-radius: 999px;
  background: #ffffff;
  font-size: 12px;
  font-family: inherit;
  color: var(--rc-text-secondary);
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;

  &:hover {
    border-color: var(--rc-primary);
    box-shadow: 0 0 0 3px rgba(47, 84, 235, 0.08);
  }
}

.svc-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--rc-text-faint);
}

.svc-chip.up .svc-dot {
  background: var(--rc-success);
  box-shadow: 0 0 0 3px rgba(22, 163, 74, 0.15);
}

.svc-chip.down .svc-dot {
  background: var(--rc-danger);
  box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.15);
}

.svc-chip.checking .svc-dot {
  background: var(--rc-warning);
  animation: svc-pulse 1.1s ease-in-out infinite;
}

@keyframes svc-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

.user-chip {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-8);
  color: var(--rc-primary);
  font-size: 13px;
  font-weight: 600;
}

.user-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);
}

.user-role {
  font-size: 11px;
  color: var(--rc-text-muted);
}

// ---------- Content ----------
.content {
  flex: 1;
  overflow: auto;
  padding: 24px 28px 48px;
  background-image: linear-gradient(rgba(28, 35, 51, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(28, 35, 51, 0.025) 1px, transparent 1px);
  background-size: 28px 28px;
}
</style>
