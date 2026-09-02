<template>
  <div class="dashboard">
    <header class="dash-hero">
      <div>
        <span class="kicker">RCDIS AGENT · 实验室经费管理</span>
        <h2 class="dash-title">{{ greeting() }}，管理员</h2>
        <p class="dash-sub">{{ formatToday() }} · 以下是平台整体运行情况</p>
      </div>
      <div class="dash-actions">
        <el-button type="primary" @click="router.push('/chat')">
          <el-icon style="margin-right: 6px"><ChatDotRound /></el-icon>发起对话
        </el-button>
        <el-button @click="router.push('/providers')">模型设置</el-button>
      </div>
    </header>

    <div class="stat-grid">
      <StatCard
        icon="Folder"
        label="科研项目"
        :value="String(projectsStore.totalCount)"
        unit="个"
        sub="已入库科研项目"
        tone="primary"
      />
      <StatCard
        icon="Coin"
        label="经费总额"
        :value="formatMoney(projectsStore.totalBudget)"
        sub="在管项目预算合计"
        tone="success"
      />
      <StatCard
        icon="Cpu"
        label="可用模型"
        :value="`${providersStore.enabledRecords.length} / ${providersStore.records.length}`"
        unit="个"
        :sub="defaultProviderLabel"
        tone="primary"
      />
      <StatCard
        icon="CircleCheck"
        label="后端服务"
        :value="serviceLabel"
        :sub="serviceSub"
        :tone="serviceTone"
      />
    </div>

    <div class="dash-grid">
      <div class="rc-card dash-panel">
        <div class="panel-head">
          <div>
            <span class="kicker">FUND OVERVIEW</span>
            <h3>项目经费总览</h3>
          </div>
          <el-button text type="primary" @click="router.push('/projects')">
            查看全部<el-icon style="margin-left: 4px"><ArrowRight /></el-icon>
          </el-button>
        </div>

        <div v-if="projectsStore.loading && !projectsStore.loaded" class="panel-body">
          <el-skeleton :rows="5" animated />
        </div>
        <el-alert
          v-else-if="projectsStore.error"
          type="error"
          :title="projectsStore.error"
          :closable="false"
          show-icon
        >
          <el-button size="small" type="primary" plain @click="projectsStore.load(true)">重试</el-button>
        </el-alert>
        <div v-else-if="projectsStore.totalCount === 0" class="panel-body">
          <EmptyBlock
            icon="Folder"
            title="暂无科研项目数据"
            description="请先通过后端接口或数据库初始化项目与预算信息"
          />
        </div>
        <ul v-else class="fund-list">
          <li v-for="project in topProjects" :key="project.id" class="fund-item">
            <div class="fund-info">
              <span class="fund-name">{{ project.projectName }}</span>
              <span class="fund-code num">{{ project.projectCode }}</span>
            </div>
            <span class="fund-pi">{{ project.principalInvestigator || '未指定负责人' }}</span>
            <span class="fund-amount num">{{ formatMoney(project.totalBudget) }}</span>
            <el-tag size="small" :type="projectStatusMeta(project.status).tagType" effect="light">
              {{ projectStatusMeta(project.status).label }}
            </el-tag>
          </li>
        </ul>
        <p v-if="projectsStore.totalCount > 0" class="fund-note">
          预算执行数据将随支出管理模块上线后在此展示
        </p>
      </div>

      <div class="rc-card dash-panel">
        <div class="panel-head">
          <div>
            <span class="kicker">ROADMAP</span>
            <h3>模块建设进度</h3>
          </div>
        </div>
        <ul class="roadmap-list">
          <li v-for="item in ROADMAP" :key="item.name" class="roadmap-item">
            <div class="roadmap-info">
              <span class="roadmap-name">
                {{ item.name }}
                <em class="num">{{ item.en }}</em>
              </span>
              <span class="roadmap-desc">{{ item.description }}</span>
            </div>
            <el-tag size="small" :type="ROADMAP_STATE[item.state].tagType" effect="light">
              {{ ROADMAP_STATE[item.state].label }}
            </el-tag>
          </li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import EmptyBlock from '@/components/EmptyBlock.vue'
import StatCard from '@/components/StatCard.vue'
import { useAppStore } from '@/stores/app'
import { useProjectsStore } from '@/stores/projects'
import { useProvidersStore } from '@/stores/providers'
import { ROADMAP, ROADMAP_STATE, projectStatusMeta } from '@/utils/constants'
import { formatMoney, formatTimeShort, formatToday, greeting } from '@/utils/format'

const router = useRouter()
const appStore = useAppStore()
const projectsStore = useProjectsStore()
const providersStore = useProvidersStore()

const topProjects = computed(() => projectsStore.projects.slice(0, 6))

const defaultProviderLabel = computed(() => {
  const record = providersStore.defaultRecord
  return record ? `默认模型：${record.name}` : '尚未配置默认模型'
})

const serviceLabel = computed(() => {
  switch (appStore.healthState) {
    case 'up':
      return '正常'
    case 'down':
      return '离线'
    case 'checking':
      return '检测中'
    default:
      return '未连接'
  }
})

const serviceTone = computed(() => {
  switch (appStore.healthState) {
    case 'up':
      return 'success' as const
    case 'down':
      return 'danger' as const
    default:
      return 'neutral' as const
  }
})

const serviceSub = computed(() => {
  if (appStore.healthState === 'up' && appStore.health) {
    return `Spring Boot · ${formatTimeShort(appStore.health.time)}`
  }
  return 'Spring Boot 后端未连接'
})

onMounted(() => {
  void projectsStore.load()
  void providersStore.load()
})
</script>

<style scoped lang="scss">
.dashboard {
  max-width: 1280px;
  margin: 0 auto;
}

.dash-hero {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.dash-title {
  margin: 8px 0 0;
  font-size: 22px;
  font-weight: 650;
  color: var(--rc-text);
  letter-spacing: 0.01em;
}

.dash-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--rc-text-muted);
}

.dash-actions {
  display: flex;
  gap: 10px;
  padding-bottom: 4px;
}

.stat-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px;
}

.dash-grid {
  display: grid;
  grid-template-columns: 1.6fr 1fr;
  gap: 16px;
  margin-top: 16px;
  align-items: start;
}

.dash-panel {
  padding: 20px;
}

.panel-body {
  padding: 8px 0;
}

.fund-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.fund-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px dashed var(--rc-line);

  &:last-child {
    border-bottom: none;
  }
}

.fund-info {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.fund-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--rc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fund-code {
  font-size: 11px;
  color: var(--rc-text-muted);
  margin-top: 2px;
}

.fund-pi {
  width: 110px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--rc-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fund-amount {
  width: 120px;
  flex-shrink: 0;
  text-align: right;
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);
}

.fund-note {
  margin: 12px 0 0;
  padding-top: 12px;
  border-top: 1px solid var(--rc-line);
  font-size: 12px;
  color: var(--rc-text-faint);
}

.roadmap-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.roadmap-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 11px 0;
  border-bottom: 1px dashed var(--rc-line);

  &:last-child {
    border-bottom: none;
  }
}

.roadmap-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.roadmap-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);

  em {
    margin-left: 6px;
    font-style: normal;
    font-size: 10px;
    font-weight: 500;
    letter-spacing: 0.1em;
    text-transform: uppercase;
    color: var(--rc-text-faint);
  }
}

.roadmap-desc {
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--rc-text-muted);
}

@media (max-width: 1200px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .dash-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .stat-grid {
    grid-template-columns: 1fr;
  }
}
</style>
