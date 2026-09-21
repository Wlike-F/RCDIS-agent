<template>
  <section class="task-card" aria-label="Agent 任务执行状态">
    <header class="task-head">
      <div>
        <p class="task-title">{{ task.title }}</p>
        <p class="task-meta">任务 #{{ task.id }} · {{ progressText }}</p>
      </div>
      <el-tag :type="tagType" effect="plain">{{ statusLabel }}</el-tag>
    </header>
    <el-progress
      :percentage="percentage"
      :status="progressStatus"
      :stroke-width="8"
      :show-text="false"
    />
    <ol class="task-steps">
      <li v-for="step in task.steps" :key="step.id" class="task-step">
        <span class="step-index">{{ step.stepNo }}</span>
        <div class="step-main">
          <div class="step-line">
            <span>{{ actionLabel(step.action) }}</span>
            <el-tag size="small" :type="stepTagType(step.status)" effect="plain">
              {{ statusText(step.status) }}
            </el-tag>
          </div>
          <p class="step-target">{{ step.targetType }} #{{ step.targetId }}</p>
          <p v-if="step.errorMessage" class="step-error">{{ step.errorMessage }}</p>
        </div>
      </li>
    </ol>
    <el-alert
      v-if="task.errorMessage"
      type="error"
      :title="task.errorMessage"
      :closable="false"
      show-icon
    />
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { AgentTaskVO } from '@/api/types'

const props = defineProps<{ task: AgentTaskVO }>()

const percentage = computed(() =>
  props.task.totalSteps > 0
    ? Math.round(((props.task.completedSteps + props.task.failedSteps) / props.task.totalSteps) * 100)
    : 0
)
const progressText = computed(
  () => `${props.task.completedSteps} 成功 / ${props.task.failedSteps} 失败 / ${props.task.totalSteps} 步`
)
const statusLabel = computed(() => statusText(props.task.status))
const tagType = computed(() => stepTagType(props.task.status))
const progressStatus = computed<'success' | 'exception' | undefined>(() => {
  if (props.task.status === 'SUCCEEDED') return 'success'
  if (props.task.status === 'FAILED' || props.task.status === 'PARTIAL') return 'exception'
  return undefined
})

function statusText(status: string): string {
  return ({
    PLANNED: '已规划',
    WAITING_CONFIRMATION: '等待确认',
    RUNNING: '执行中',
    SUCCEEDED: '已成功',
    FAILED: '执行失败',
    PARTIAL: '部分成功'
  } as Record<string, string>)[status] ?? status
}

function stepTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED' || status === 'PARTIAL') return 'danger'
  if (status === 'RUNNING' || status === 'WAITING_CONFIRMATION') return 'warning'
  return 'info'
}

function actionLabel(action: string): string {
  return action.toUpperCase() === 'SUBMIT_REIMBURSEMENT' ? '提交报销单' : action
}
</script>

<style scoped lang="scss">
.task-card { width: 100%; padding: 14px; border: 1px solid #dce4f5; border-radius: 10px; background: #f8faff; }
.task-head, .step-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.task-title { margin: 0; color: var(--rc-text); font-size: 13px; font-weight: 600; }
.task-meta, .step-target, .step-error { margin: 4px 0 0; font-size: 11.5px; color: var(--rc-text-muted); }
.task-head + :deep(.el-progress) { margin-top: 12px; }
.task-steps { margin: 12px 0 0; padding: 0; list-style: none; display: grid; gap: 8px; }
.task-step { display: flex; gap: 9px; padding-top: 8px; border-top: 1px dashed var(--rc-line); }
.step-index { display: grid; place-items: center; width: 22px; height: 22px; flex: 0 0 22px; border-radius: 50%; background: #e8edfb; color: var(--rc-primary); font-size: 11px; font-weight: 600; }
.step-main { flex: 1; min-width: 0; }
.step-line { color: var(--rc-text-secondary); font-size: 12.5px; }
.step-error { color: var(--rc-danger); }
.task-steps + :deep(.el-alert) { margin-top: 10px; }
</style>
