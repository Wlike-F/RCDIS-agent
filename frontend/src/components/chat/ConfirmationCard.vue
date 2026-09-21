<template>
  <div class="confirm-card">
    <div class="confirm-head">
      <el-icon class="confirm-icon" :size="20"><WarningFilled /></el-icon>
      <div class="confirm-head-text">
        <p class="confirm-title">高风险操作等待确认</p>
        <p class="confirm-sub">该操作会修改经费数据，确认后才会执行并写入审计日志</p>
      </div>
    </div>

    <div class="confirm-fields">
      <div v-for="row in rows" :key="row.label" class="confirm-field">
        <span class="confirm-label">{{ row.label }}</span>
        <span class="confirm-value" :class="{ pre: row.multi }">{{ row.value }}</span>
      </div>
    </div>

    <div v-if="!confirmation.resolved" class="confirm-actions">
      <el-button v-if="canResolve" type="primary" :loading="confirmation.resolving" @click="$emit('resolve', true)">
        确认执行
      </el-button>
      <el-button :disabled="confirmation.resolving" @click="$emit('resolve', false)">
        取消操作
      </el-button>
    </div>

    <el-alert
      v-if="!confirmation.resolved && !canResolve"
      class="confirm-result"
      type="warning"
      title="当前账号不能执行该写操作，但可以取消该提案。"
      :closable="false"
      show-icon
    />

    <el-alert
      v-if="confirmation.resolved"
      class="confirm-result"
      :type="alertType"
      :title="confirmation.resultNote ?? ''"
      :closable="false"
      show-icon
    />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { Confirmation } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'

const props = defineProps<{ confirmation: Confirmation }>()
const authStore = useAuthStore()

defineEmits<{ (e: 'resolve', approved: boolean): void }>()

function stringify(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

const rows = computed(() => {
  const fields: Array<{ label: string; value: string }> = [
    { label: '操作类型', value: props.confirmation.operation },
    {
      label: '目标对象',
      value: [props.confirmation.targetType, props.confirmation.targetId]
        .filter(Boolean)
        .join(' · ')
    },
    { label: '操作说明', value: props.confirmation.summary },
    { label: '变更前', value: props.confirmation.before },
    { label: '变更后', value: props.confirmation.after },
    { label: '操作原因', value: props.confirmation.reason },
    { label: '影响范围', value: props.confirmation.scope }
  ]
  const filled = fields.filter((row) => row.value)
  if (filled.length > 0) {
    return filled.map((row) => ({ ...row, multi: row.value.includes('\n') }))
  }
  return [{ label: '确认内容', value: stringify(props.confirmation.raw), multi: true }]
})

const alertType = computed(() => props.confirmation.resultLevel ?? 'info')
const canResolve = computed(() => authStore.hasAnyRole('ADMIN', 'RESEARCHER'))
</script>

<style scoped lang="scss">
.confirm-card {
  width: 100%;
  padding: 14px 16px;
  border: 1px solid #f2e3c8;
  border-radius: 10px;
  background: #fffdf7;
}

.confirm-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.confirm-icon {
  color: var(--rc-warning);
  margin-top: 2px;
  flex-shrink: 0;
}

.confirm-head-text {
  min-width: 0;
}

.confirm-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--rc-text);
}

.confirm-sub {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--rc-text-muted);
}

.confirm-fields {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed #efe3cb;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.confirm-field {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.confirm-label {
  width: 64px;
  flex-shrink: 0;
  font-size: 12px;
  color: var(--rc-text-muted);
  line-height: 1.7;
}

.confirm-value {
  font-size: 13px;
  color: var(--rc-text);
  line-height: 1.7;
  word-break: break-all;
  min-width: 0;

  &.pre {
    white-space: pre-wrap;
    font-family: var(--rc-font-mono);
    font-size: 12px;
  }
}

.confirm-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.confirm-result {
  margin-top: 12px;
}
</style>
