<template>
  <div class="needs-input" :class="{ resolved: needsInput.resolved }">
    <div class="needs-head">
      <el-icon class="needs-icon"><WarningFilled /></el-icon>
      <div class="needs-title">
        <b>{{ headline }}</b>
        <span class="needs-sub">{{ needsInput.reason }}</span>
      </div>
    </div>

    <div v-if="needsInput.missingFields.length" class="needs-fields">
      <div v-for="field in needsInput.missingFields" :key="field" class="needs-field">
        <label :for="inputId(field)">{{ fieldLabel(field) }}</label>
        <el-input
          :id="inputId(field)"
          v-model="values[field]"
          :placeholder="fieldPlaceholder(field)"
          size="small"
          :disabled="needsInput.resolved"
        />
      </div>
    </div>
    <div v-else class="needs-fields needs-fields-empty">
      <span class="needs-empty-hint">
        未提供具体字段。可点击下方按钮发送一句 "换个说法再试"，或直接补充信息后重新提问。
      </span>
    </div>

    <div v-if="needsInput.mustCall.length" class="needs-must">
      <span class="needs-must-label">建议下一步调用：</span>
      <el-tag v-for="tool in needsInput.mustCall" :key="tool" size="small" type="info">
        {{ tool }}
      </el-tag>
    </div>

    <div class="needs-actions">
      <el-button
        type="primary"
        size="small"
        :disabled="needsInput.resolved || !hasValue || streaming"
        @click="submit"
      >
        {{ needsInput.resolved ? '已提交' : '补齐并提交' }}
      </el-button>
      <el-button
        size="small"
        text
        :disabled="needsInput.resolved || streaming"
        @click="cancel"
      >
        我自己打字
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, computed, watch } from 'vue'

import type { NeedsInput } from '@/stores/chat'

const props = defineProps<{
  needsInput: NeedsInput
  streaming?: boolean
}>()

const emit = defineEmits<{
  (e: 'submit', fieldValues: Record<string, string>): void
  (e: 'cancel'): void
}>()

const values = reactive<Record<string, string>>({})

// Reset the draft when a new halt arrives on the same message (e.g. the model retried a
// different tool after the user's initial submit failed again).
watch(
  () => props.needsInput,
  (next) => {
    for (const key of Object.keys(values)) delete values[key]
    if (next) for (const field of next.missingFields) values[field] = ''
  },
  { immediate: true }
)

const hasValue = computed(() =>
  props.needsInput.missingFields.some((field) => (values[field] ?? '').trim().length > 0)
)

const headline = computed(() => {
  if (props.needsInput.tier === 'FREEZE') return '工具本轮已被暂停'
  if (props.needsInput.tier === 'CEILING') return '本轮工具调用已达上限'
  return '需要补充信息才能继续'
})

function fieldLabel(field: string): string {
  switch (field) {
    case 'projectCode':
      return '项目编号'
    case 'reimbursementNo':
      return '报销单号'
    case 'receiptId':
      return '凭证 ID'
    default:
      return field
  }
}

function fieldPlaceholder(field: string): string {
  switch (field) {
    case 'projectCode':
      return '例如 P-2026-001；不知道可先输入 P 让助手列出候选'
    case 'reimbursementNo':
      return '例如 R-2026-0001'
    case 'receiptId':
      return '对话里上传凭证后系统返回的 receiptId'
    default:
      return '请填写值'
  }
}

function inputId(field: string): string {
  return `needs-input-${field}`
}

function submit() {
  if (props.needsInput.resolved || props.streaming) return
  const cleaned: Record<string, string> = {}
  for (const [key, value] of Object.entries(values)) {
    if (typeof value === 'string' && value.trim()) cleaned[key] = value.trim()
  }
  if (Object.keys(cleaned).length === 0) return
  emit('submit', cleaned)
}

function cancel() {
  emit('cancel')
}
</script>

<style scoped lang="scss">
.needs-input {
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid var(--el-color-warning-light-5);
  border-left: 3px solid var(--rc-warning);
  border-radius: 10px;
  background: var(--el-color-warning-light-9);
  display: flex;
  flex-direction: column;
  gap: 10px;

  &.resolved {
    border-color: var(--rc-line);
    background: #fafbfe;
    opacity: 0.85;
  }
}

.needs-head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
}

.needs-icon {
  color: var(--rc-warning);
  flex-shrink: 0;
  margin-top: 2px;
}

.needs-title {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;

  b {
    font-size: 13px;
    color: var(--rc-text);
  }
}

.needs-sub {
  font-size: 12px;
  line-height: 1.6;
  color: var(--rc-text-secondary);
  word-break: break-all;
}

.needs-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.needs-field {
  display: flex;
  flex-direction: column;
  gap: 4px;

  label {
    font-size: 12px;
    color: var(--rc-text-muted);
  }
}

.needs-fields-empty {
  padding: 6px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.6);
  font-size: 12px;
  color: var(--rc-text-secondary);
  line-height: 1.6;
}

.needs-must {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: 11.5px;
  color: var(--rc-text-muted);
}

.needs-must-label {
  margin-right: 4px;
}

.needs-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
