<template>
  <div class="trace-panel">
    <div class="trace-head">
      <div>
        <span class="kicker">CONTEXT TRACE</span>
        <h3>上下文轨迹</h3>
      </div>
      <el-button size="small" :loading="traceStore.loading" @click="reload">
        <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <el-alert
      v-if="traceStore.error"
      type="error"
      :title="traceStore.error"
      :closable="false"
      show-icon
      class="trace-alert"
    />

    <div v-if="!conversationId" class="trace-empty">
      <EmptyBlock icon="DataLine" title="暂无会话" description="先发起一段对话，再回到这里查看上下文轨迹" />
    </div>

    <template v-else>
      <div class="stat-row">
        <StatCard icon="ChatLineSquare" label="对话轮数" :value="traceStore.totalTurns" tone="primary" />
        <StatCard icon="Coin" label="累计 Token" :value="traceStore.totalTokens" tone="success" />
        <StatCard icon="Connection" label="工具调用" :value="traceStore.totalToolCalls" tone="warning" />
        <StatCard icon="Timer" label="平均耗时" :value="traceStore.avgTotalMs" unit="ms" tone="neutral" />
      </div>

      <div v-if="memory" class="memory-card">
        <div class="memory-head">
          <span class="kicker">MEMORY / COMPRESSION</span>
          <span class="memory-meta num">
            摘要覆盖到 turn {{ memory.summaryUptoSeq ?? 0 }} · 窗口 {{ memory.windowMessages }} 轮 / {{ memory.windowTokens }} token · 压缩 {{ memory.compressCount ?? 0 }} 次
          </span>
        </div>
        <el-collapse v-if="memory.rollingSummary" class="memory-collapse">
          <el-collapse-item title="滚动摘要" name="summary">
            <p class="memory-summary">{{ memory.rollingSummary }}</p>
          </el-collapse-item>
        </el-collapse>
        <div v-if="memory.facts && memory.facts.length" class="memory-facts">
          <span class="facts-label">硬事实要点</span>
          <div class="facts-chips">
            <el-tag
              v-for="(fact, i) in memory.facts"
              :key="i"
              size="small"
              effect="plain"
              :type="factTag(fact.type)"
              class="fact-chip"
            >
              [{{ fact.type }}] {{ fact.text }} <span class="fact-seq num">t{{ fact.turnSeq }}</span>
            </el-tag>
          </div>
        </div>
        <p v-else class="memory-empty">尚未压缩（会话较短，全部原文仍在窗口内）</p>
      </div>

      <el-skeleton v-if="traceStore.loading && traceStore.traces.length === 0" :rows="6" animated />
      <EmptyBlock
        v-else-if="traceStore.traces.length === 0"
        icon="DataLine"
        title="暂无轨迹"
        description="该会话还没有已完成的对话轮次"
      />

      <el-timeline v-else class="trace-timeline">
        <el-timeline-item
          v-for="trace in traceStore.traces"
          :key="trace.id"
          :type="timelineType(trace.status)"
          :timestamp="formatDateTime(trace.createdAt)"
          placement="top"
        >
          <div class="turn-card">
            <div class="turn-head">
              <span class="turn-seq num">#{{ trace.turnSeq }}</span>
              <span class="turn-model mono">{{ trace.modelName || trace.providerCode || '--' }}</span>
              <el-tag size="small" :type="statusTag(trace.status)" effect="light">{{ trace.status }}</el-tag>
            </div>

            <div class="metric-grid">
              <div class="metric">
                <span>输入 Token</span>
                <b class="num">{{ trace.promptTokens ?? '--' }}</b>
              </div>
              <div class="metric">
                <span>输出 Token</span>
                <b class="num">{{ trace.completionTokens ?? '--' }}</b>
              </div>
              <div class="metric">
                <span>首字延迟</span>
                <b class="num">{{ trace.firstTokenMs != null ? trace.firstTokenMs + ' ms' : '--' }}</b>
              </div>
              <div class="metric">
                <span>总耗时</span>
                <b class="num">{{ trace.totalMs != null ? trace.totalMs + ' ms' : '--' }}</b>
              </div>
            </div>

            <div class="bar-row">
              <span class="bar-label">耗时</span>
              <div class="bar-track">
                <div class="bar-fill latency" :style="{ width: latencyWidth(trace) }"></div>
              </div>
              <span class="bar-value num">{{ trace.totalMs ?? 0 }} ms</span>
            </div>
            <div class="bar-row">
              <span class="bar-label">Token</span>
              <div class="bar-track">
                <div class="bar-fill tokens" :style="{ width: tokenWidth(trace) }"></div>
              </div>
              <span class="bar-value num">{{ trace.totalTokens ?? 0 }}</span>
            </div>

            <div v-if="trace.toolCalls && trace.toolCalls.length" class="tool-chain">
              <span class="chain-label">工具调用链</span>
              <div class="chain-tags">
                <el-tag
                  v-for="(call, index) in trace.toolCalls"
                  :key="index"
                  size="small"
                  :type="call.ok ? 'success' : 'danger'"
                  effect="plain"
                  class="chain-tag mono"
                >
                  {{ index + 1 }}. {{ call.name }} · {{ call.durationMs }}ms
                </el-tag>
              </div>
            </div>

            <p v-if="trace.errorMessage" class="turn-error">{{ trace.errorMessage }}</p>
          </div>
        </el-timeline-item>
      </el-timeline>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { api } from '@/api'
import EmptyBlock from '@/components/EmptyBlock.vue'
import StatCard from '@/components/StatCard.vue'
import type { AgentMemoryVO, AgentTurnTraceVO } from '@/api/types'
import { useChatStore } from '@/stores/chat'
import { useTraceStore } from '@/stores/trace'
import { formatDateTime } from '@/utils/format'

const chatStore = useChatStore()
const traceStore = useTraceStore()

const conversationId = computed(() => chatStore.activeConversation?.id ?? null)
const memory = ref<AgentMemoryVO | null>(null)

async function loadMemory() {
  if (!conversationId.value) {
    memory.value = null
    return
  }
  try {
    memory.value = await api.getChatMemory(conversationId.value)
  } catch {
    memory.value = null
  }
}

function reload() {
  void traceStore.load(conversationId.value, true)
  void loadMemory()
}

function factTag(type: string | null): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  switch (type) {
    case 'amount':
      return 'success'
    case 'order':
    case 'todo':
      return 'warning'
    case 'project':
    case 'decision':
      return 'primary'
    default:
      return 'info'
  }
}

function timelineType(status: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'DONE') return 'success'
  if (status === 'TIMEOUT') return 'warning'
  if (status === 'ERROR') return 'danger'
  return 'info'
}

function statusTag(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'DONE') return 'success'
  if (status === 'TIMEOUT') return 'warning'
  if (status === 'ERROR') return 'danger'
  return 'info'
}

function latencyWidth(trace: AgentTurnTraceVO): string {
  const max = traceStore.maxTotalMs || 1
  const value = trace.totalMs ?? 0
  return Math.max(2, Math.round((value / max) * 100)) + '%'
}

function tokenWidth(trace: AgentTurnTraceVO): string {
  const max = traceStore.maxTokens || 1
  const value = trace.totalTokens ?? 0
  return Math.max(2, Math.round((value / max) * 100)) + '%'
}

watch(conversationId, (id) => {
  void traceStore.load(id)
  void loadMemory()
})

onMounted(() => {
  void traceStore.load(conversationId.value)
  void loadMemory()
})
</script>

<style scoped lang="scss">
.trace-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.memory-card {
  padding: 12px 14px;
  border: 1px solid var(--rc-line);
  border-radius: 10px;
  background: #fbfcfe;
}

.memory-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.memory-meta {
  font-size: 11px;
  color: var(--rc-text-faint);
}

.memory-collapse {
  margin-top: 8px;
}

.memory-summary {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--rc-text-secondary);
  white-space: pre-wrap;
}

.memory-facts {
  margin-top: 10px;
}

.facts-label {
  display: block;
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--rc-text-muted);
}

.facts-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.fact-chip {
  max-width: 100%;
  white-space: normal;
  height: auto;
  padding: 3px 8px;
  line-height: 1.5;
}

.fact-seq {
  opacity: 0.7;
}

.memory-empty {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--rc-text-faint);
}

.trace-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;

  h3 {
    margin: 6px 0 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--rc-text);
  }
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.trace-timeline {
  padding-left: 4px;
}

.turn-card {
  padding: 12px 14px;
  border: 1px solid var(--rc-line);
  border-radius: 10px;
  background: var(--rc-surface);
}

.turn-head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.turn-seq {
  font-weight: 600;
  color: var(--rc-primary-strong);
}

.turn-model {
  font-size: 12px;
  color: var(--rc-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;
}

.metric {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  border-radius: 8px;
  background: #f7f9fd;

  span {
    font-size: 11px;
    color: var(--rc-text-muted);
  }

  b {
    font-size: 13px;
    font-weight: 600;
    color: var(--rc-text);
  }
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.bar-label {
  width: 44px;
  flex-shrink: 0;
  font-size: 11px;
  color: var(--rc-text-muted);
}

.bar-track {
  flex: 1;
  height: 8px;
  border-radius: 4px;
  background: #eef1f7;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 4px;

  &.latency {
    background: linear-gradient(90deg, #2f54eb, #5e7bf7);
  }

  &.tokens {
    background: linear-gradient(90deg, #16a34a, #4ade80);
  }
}

.bar-value {
  width: 72px;
  flex-shrink: 0;
  text-align: right;
  font-size: 11px;
  color: var(--rc-text-secondary);
}

.tool-chain {
  margin-top: 10px;
}

.chain-label {
  display: block;
  margin-bottom: 6px;
  font-size: 11px;
  color: var(--rc-text-muted);
}

.chain-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.turn-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--rc-danger);
}

.trace-empty {
  padding: 24px 0;
}

@media (max-width: 900px) {
  .stat-row,
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
