<template>
  <div class="observability-page">
    <PageHeader
      kicker="AGENT OBSERVABILITY"
      title="Agent 运行观测"
      description="从模型响应、工具执行到任务恢复，集中查看智能体的运行质量与可靠性信号。"
    >
      <template #actions>
        <span class="updated-at" v-if="lastUpdated">更新于 {{ lastUpdated }}</span>
        <el-select v-model="periodDays" class="period-select" @change="loadMetrics">
          <el-option label="近 1 天" :value="1" />
          <el-option label="近 7 天" :value="7" />
          <el-option label="近 14 天" :value="14" />
          <el-option label="近 15 天（保留上限）" :value="15" />
        </el-select>
        <el-button :loading="loading" @click="loadMetrics">
          <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新指标
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="error" type="warning" :title="error" :closable="false" show-icon class="metric-alert" />

    <section class="signal-strip">
      <div class="signal-copy">
        <span class="kicker">LIVE SIGNAL</span>
        <h2>让每一次 Agent 决策都可解释、可追踪</h2>
        <p>请求量/Token/耗时来自持久化轨迹（agent_turn_trace）按窗口聚合，跨重启可查；质量信号为进程内实时快照；轨迹明细保留 15 天（每日 TTL 清理）。</p>
      </div>
      <div class="signal-status">
        <span class="status-pulse" :class="{ active: !error }"></span>
        <span>{{ error ? '指标源不可用' : '指标源已连接' }}</span>
        <code>/api/admin/agent-metrics/period?days={{ periodDays }}</code>
      </div>
    </section>

    <section class="metric-grid primary-grid">
      <article v-for="card in primaryCards" :key="card.label" class="metric-card" :class="card.tone">
        <div class="metric-card-head">
          <span>{{ card.label }}</span>
          <el-icon><component :is="card.icon" /></el-icon>
        </div>
        <div class="metric-value">{{ card.value }}</div>
        <div class="metric-foot">
          <span>{{ card.description }}</span>
          <el-tag v-if="card.available" size="small" type="success" effect="plain">已接入</el-tag>
          <el-tag v-else size="small" type="info" effect="plain">待接入埋点</el-tag>
        </div>
      </article>
    </section>

    <div class="content-grid">
      <section class="rc-card panel">
        <div class="panel-heading">
          <div>
            <span class="kicker">QUALITY & SAFETY</span>
            <h3>Agent 质量信号</h3>
          </div>
          <span class="panel-note">进程内实时快照（重启归零）</span>
        </div>
        <div class="quality-list">
          <div v-for="item in qualityMetrics" :key="item.label" class="quality-row">
            <div class="quality-label"><span class="quality-dot" :class="item.tone"></span>{{ item.label }}</div>
            <strong>{{ item.value }}</strong>
            <span class="quality-desc">{{ item.description }}</span>
          </div>
        </div>
      </section>

      <section class="rc-card panel">
        <div class="panel-heading">
          <div>
            <span class="kicker">RUNTIME</span>
            <h3>运行资源</h3>
          </div>
          <span class="panel-note">近 {{ periodDays }} 天 · 持久化轨迹聚合</span>
        </div>
        <div class="runtime-list">
          <div v-for="item in runtimeMetrics" :key="item.label" class="runtime-row">
            <div class="runtime-copy"><span>{{ item.label }}</span><small>{{ item.description }}</small></div>
            <div class="runtime-value">{{ item.value }}</div>
          </div>
        </div>
      </section>
    </div>

    <section class="rc-card panel tools-panel">
      <div class="panel-heading">
        <div>
          <span class="kicker">INSTRUMENTATION ROADMAP</span>
          <h3>关键指标接入清单</h3>
        </div>
        <el-tag type="info" effect="plain">{{ connectedCount }}/{{ roadmapMetrics.length }} 已接入</el-tag>
      </div>
      <div class="roadmap-grid">
        <div v-for="item in roadmapMetrics" :key="item.label" class="roadmap-item">
          <div class="roadmap-check" :class="{ connected: item.connected }">
            <el-icon><CircleCheck v-if="item.connected" /><Clock v-else /></el-icon>
          </div>
          <div><strong>{{ item.label }}</strong><p>{{ item.description }}</p></div>
          <span class="roadmap-state">{{ item.connected ? '已接入' : '待接入' }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { api } from '@/api'
import type { AgentMetricBreakdownVO, AgentMetricsPeriodVO, AgentMetricsSummaryVO } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'

interface DisplayMetric { label: string; value: string; description: string; tone: string; icon: string; available: boolean }
interface RoadmapMetric { label: string; description: string; connected: boolean }

const loading = ref(false)
const error = ref('')
const lastUpdated = ref('')
const summary = ref<AgentMetricsSummaryVO | null>(null)
const period = ref<AgentMetricsPeriodVO | null>(null)
const periodDays = ref(7)

function formatCount(value: number | null | undefined): string {
  return value == null ? '—' : new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(value)
}

function formatMilliseconds(value: number | null | undefined): string {
  if (value == null) return '—'
  return value < 1000 ? `${Math.round(value)} ms` : `${(value / 1000).toFixed(2)} s`
}

function formatPercentage(value: number | null | undefined): string {
  return value == null ? '—' : `${(value * 100).toFixed(1)}%`
}

function breakdownValue(metric: AgentMetricBreakdownVO | undefined, key: string): number {
  if (!metric) return 0
  const entry = Object.entries(metric.values).find(([name]) => name.toLowerCase() === key.toLowerCase())
  return entry?.[1] ?? 0
}

function formatGeneratedAt(value: string): string {
  const generatedAt = new Date(value)
  return Number.isNaN(generatedAt.getTime())
    ? new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
    : generatedAt.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

async function loadMetrics(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const [nextSummary, nextPeriod] = await Promise.all([
      api.getAgentMetricsSummary(),
      api.getAgentMetricsPeriod(periodDays.value)
    ])
    summary.value = nextSummary
    period.value = nextPeriod
    lastUpdated.value = formatGeneratedAt(nextPeriod.generatedAt)
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '无法读取 Agent 指标'
  } finally {
    loading.value = false
  }
}

const primaryCards = computed<DisplayMetric[]>(() => [
  { label: 'Agent 请求量', value: formatCount(period.value?.turns), description: `近 ${periodDays.value} 天模型回合数（落库可查）`, tone: 'blue', icon: 'Connection', available: period.value !== null },
  { label: '失败 / 超时轮次', value: formatCount(period.value ? period.value.errorTurns + period.value.timeoutTurns : null), description: `近 ${periodDays.value} 天 status=ERROR/TIMEOUT`, tone: 'green', icon: 'Refresh', available: period.value !== null },
  { label: 'Token 总量', value: formatCount(period.value?.totalTokens), description: `近 ${periodDays.value} 天输入+输出（轨迹落库）`, tone: 'violet', icon: 'Tickets', available: period.value !== null },
  { label: 'Token 成本', value: period.value?.costConfigured ? period.value.estimatedCost.toFixed(4) : '待配置费率', description: `近 ${periodDays.value} 天按配置费率估算`, tone: 'amber', icon: 'Coin', available: period.value?.costConfigured === true }
])

const qualityMetrics = computed<DisplayMetric[]>(() => [
  { label: 'Tool 调用成功率', value: formatPercentage(period.value?.toolSuccessRate), description: `${formatCount(period.value?.toolCallsSuccess)} 成功 / ${formatCount(period.value?.toolCallsTotal)} 次`, tone: 'green', icon: 'Tools', available: period.value !== null },
  { label: '参数校验失败率', value: '—', description: 'Tool 入参被业务层拒绝的比例', tone: 'amber', icon: 'Warning', available: false },
  { label: '确认提案拒绝率', value: confirmationRejectRate(), description: '用户拒绝高风险操作的比例', tone: 'violet', icon: 'CircleClose', available: summary.value !== null },
  { label: '安全策略拦截', value: formatCount(summary.value?.securityBlocks.total), description: '服务端权限与安全策略拦截次数', tone: 'red', icon: 'Lock', available: summary.value !== null },
  { label: '长期记忆命中率', value: formatPercentage(summary.value?.memory.hitRate), description: `${formatCount(summary.value?.memory.hits)} 命中 / ${formatCount(summary.value?.memory.totalInjections)} 次`, tone: 'blue', icon: 'Collection', available: summary.value !== null },
  { label: 'Task / Step 失败率', value: taskStepFailureRate(), description: '复杂任务步骤执行失败比例', tone: 'red', icon: 'Warning', available: summary.value !== null }
])

const runtimeMetrics = computed<DisplayMetric[]>(() => [
  { label: '平均首 Token', value: formatMilliseconds(period.value?.avgFirstTokenMs), description: `近 ${periodDays.value} 天 ${formatCount(firstTokenSamples())} 个响应样本`, tone: 'blue', icon: 'Timer', available: period.value !== null },
  { label: '平均回合耗时', value: formatMilliseconds(period.value?.avgTotalMs), description: `近 ${periodDays.value} 天 ${formatCount(period.value?.turns)} 个回合样本`, tone: 'green', icon: 'CircleCheck', available: period.value !== null },
  { label: '输入 Token', value: formatCount(period.value?.promptTokens), description: `近 ${periodDays.value} 天模型实际上报的输入 Token`, tone: 'violet', icon: 'Connection', available: period.value !== null },
  { label: '输出 Token', value: formatCount(period.value?.completionTokens), description: `近 ${periodDays.value} 天模型实际上报的输出 Token`, tone: 'amber', icon: 'Coin', available: period.value !== null }
])

function firstTokenSamples(): number {
  // The period VO carries the average only; the sample count equals turns with a first-token reading.
  return period.value?.turns ?? 0
}

const roadmapMetrics: RoadmapMetric[] = [
  { label: 'Tool 调用成功率', description: '工具级成功、失败及延迟分布', connected: true },
  { label: '模型首 Token 延迟', description: '按模型和供应商拆分', connected: true },
  { label: '每轮 Token / 成本', description: '输入输出 Token 与费用估算', connected: true },
  { label: '确认与拒绝率', description: '高风险操作人工决策结果', connected: true },
  { label: '任务恢复次数', description: '租约超时后的自动恢复', connected: true },
  { label: '上下文压缩次数', description: '会话上下文压缩触发次数', connected: false },
  { label: '记忆召回命中率', description: '长期记忆对回答的有效贡献', connected: true },
  { label: '不同模型 Eval 通过率', description: '模型版本横向回归结果', connected: false }
]

const connectedCount = computed(() => roadmapMetrics.filter((item) => item.connected).length)

function confirmationRejectRate(): string {
  if (!summary.value) return '—'
  const rejected = breakdownValue(summary.value.confirmations, 'rejected')
  return formatPercentage(rejected / Math.max(1, summary.value.confirmations.total))
}

function taskStepFailureRate(): string {
  if (!summary.value) return '—'
  const failed = breakdownValue(summary.value.taskSteps, 'failed')
  return formatPercentage(failed / Math.max(1, summary.value.taskSteps.total))
}

onMounted(() => { void loadMetrics() })
</script>

<style scoped lang="scss">
.observability-page { max-width: 1280px; margin: 0 auto; }
.updated-at { color: var(--rc-text-muted); font-size: 12px; margin-right: 8px; }
.period-select { width: 172px; margin-right: 8px; }
.metric-alert { margin-bottom: 16px; }
.signal-strip { display: flex; justify-content: space-between; gap: 24px; padding: 22px 24px; margin-bottom: 16px; background: #172554; color: #e0e7ff; border-radius: var(--rc-radius-lg); position: relative; overflow: hidden; }
.signal-strip::after { content: ''; position: absolute; width: 260px; height: 260px; right: 8%; top: -150px; border: 1px solid rgba(147, 197, 253, .28); border-radius: 50%; box-shadow: 0 0 0 24px rgba(147, 197, 253, .05), 0 0 0 48px rgba(147, 197, 253, .04); }
.signal-copy { position: relative; z-index: 1; }
.signal-copy .kicker { color: #93c5fd; }
.signal-copy h2 { margin: 8px 0 5px; font-size: 20px; font-weight: 650; letter-spacing: .01em; }
.signal-copy p { margin: 0; color: #bfdbfe; font-size: 13px; }
.signal-status { position: relative; z-index: 1; display: flex; align-items: center; gap: 8px; align-self: center; color: #dbeafe; font-size: 13px; white-space: nowrap; }
.signal-status code { color: #93c5fd; font-family: var(--rc-font-mono); font-size: 11px; }
.status-pulse { width: 8px; height: 8px; border-radius: 50%; background: #f59e0b; }
.status-pulse.active { background: #4ade80; box-shadow: 0 0 0 4px rgba(74, 222, 128, .16); }
.metric-grid { display: grid; gap: 14px; }
.primary-grid { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.metric-card { min-height: 140px; padding: 17px 18px; border: 1px solid var(--rc-line); border-radius: var(--rc-radius); background: var(--rc-surface); box-shadow: var(--rc-shadow-card); border-top: 3px solid var(--card-accent); }
.metric-card.blue { --card-accent: #2f54eb; }.metric-card.green { --card-accent: #16a34a; }.metric-card.violet { --card-accent: #7c3aed; }.metric-card.amber { --card-accent: #d97706; }
.metric-card-head, .metric-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: var(--rc-text-muted); font-size: 12px; }
.metric-card-head .el-icon { color: var(--card-accent); font-size: 18px; }
.metric-value { margin: 15px 0 10px; color: var(--rc-text); font-family: var(--rc-font-mono); font-size: 27px; font-weight: 650; letter-spacing: -.04em; }
.metric-foot span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.content-grid { display: grid; grid-template-columns: 1.15fr .85fr; gap: 16px; margin-top: 16px; }
.panel { padding: 20px; }
.panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.panel-heading h3 { margin: 6px 0 0; font-size: 16px; font-weight: 650; }
.panel-note { color: var(--rc-text-faint); font-size: 12px; }
.quality-list, .runtime-list { display: grid; gap: 0; }
.quality-row, .runtime-row { display: grid; align-items: center; min-height: 46px; border-bottom: 1px dashed var(--rc-line); }
.quality-row:last-child, .runtime-row:last-child { border-bottom: 0; }
.quality-row { grid-template-columns: 1.15fr 72px 1.3fr; gap: 12px; }
.quality-label { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.quality-dot { width: 7px; height: 7px; border-radius: 50%; background: #94a3b8; }.quality-dot.green { background: #16a34a; }.quality-dot.red { background: #dc2626; }.quality-dot.amber { background: #d97706; }.quality-dot.violet { background: #7c3aed; }.quality-dot.blue { background: #2f54eb; }
.quality-row strong { color: var(--rc-text); font-family: var(--rc-font-mono); text-align: right; }.quality-desc, .runtime-copy small { color: var(--rc-text-muted); font-size: 12px; }
.runtime-row { grid-template-columns: 1fr auto; gap: 16px; }.runtime-copy { display: grid; gap: 3px; font-size: 13px; }.runtime-value { color: var(--rc-text); font-family: var(--rc-font-mono); font-size: 15px; font-weight: 650; }
.tools-panel { margin-top: 16px; }
.roadmap-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 24px; }
.roadmap-item { display: grid; grid-template-columns: 28px 1fr auto; align-items: start; gap: 10px; padding: 11px 0; border-bottom: 1px dashed var(--rc-line); }.roadmap-item strong { font-size: 13px; font-weight: 600; }.roadmap-item p { margin: 3px 0 0; color: var(--rc-text-muted); font-size: 12px; }.roadmap-check { display: grid; place-items: center; width: 24px; height: 24px; color: var(--rc-text-faint); background: var(--rc-bg); border-radius: 7px; }.roadmap-check.connected { color: var(--rc-success); background: #ecfdf3; }.roadmap-state { color: var(--rc-text-faint); font-size: 12px; padding-top: 4px; }.roadmap-check.connected + div + .roadmap-state { color: var(--rc-success); }
@media (max-width: 1000px) { .primary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }.content-grid { grid-template-columns: 1fr; } }
@media (max-width: 680px) { .signal-strip { display: block; }.signal-status { margin-top: 16px; }.primary-grid, .roadmap-grid { grid-template-columns: 1fr; }.quality-row { grid-template-columns: 1fr 64px; }.quality-desc { grid-column: 1 / -1; padding-left: 15px; padding-bottom: 7px; } }
</style>
