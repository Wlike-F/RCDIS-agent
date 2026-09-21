<template>
  <div class="tool-row">
    <button class="tool-head" type="button" @click="open = !open">
      <el-icon
        class="tool-status"
        :class="{ running: tool.status === 'running', failed: tool.status === 'failed' }"
      >
        <Loading v-if="tool.status === 'running'" />
        <CircleClose v-else-if="tool.status === 'failed'" />
        <CircleCheck v-else />
      </el-icon>
      <span class="tool-label">{{ statusLabel }}</span>
      <span class="tool-name">{{ tool.name }}</span>
      <el-icon class="tool-chevron" :class="{ rotated: open }" :size="12"><ArrowRight /></el-icon>
    </button>
    <div v-show="open" class="tool-detail">
      <div v-if="tool.payload" class="tool-detail-block">
        <span class="tool-detail-label">入参</span>
        <pre class="tool-json">{{ pretty(tool.payload) }}</pre>
      </div>
      <div v-if="tool.result" class="tool-detail-block">
        <span class="tool-detail-label">结果</span>
        <pre class="tool-json">{{ pretty(tool.result) }}</pre>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

import type { ToolCall } from '@/stores/chat'

const props = defineProps<{ tool: ToolCall }>()

const open = ref(false)
const statusLabel = computed(() => {
  if (props.tool.status === 'running') return '正在调用工具'
  if (props.tool.status === 'failed') return '工具调用失败'
  return '已调用工具'
})

function pretty(value: Record<string, unknown> | null): string {
  if (!value) return ''
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
</script>

<style scoped lang="scss">
.tool-row {
  width: 100%;
  border: 1px solid var(--rc-line);
  border-radius: 8px;
  background: #fafbfe;
  overflow: hidden;
}

.tool-head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 12px;
  border: none;
  background: transparent;
  font-family: inherit;
  cursor: pointer;
  text-align: left;

  &:hover {
    background: #f4f6fc;

    .tool-chevron {
      color: var(--rc-text-muted);
    }
  }
}

.tool-status {
  color: var(--rc-success);
  flex-shrink: 0;

  &.running {
    color: var(--rc-warning);
  }

  &.failed {
    color: var(--rc-danger);
  }
}

.tool-label {
  font-size: 12px;
  color: var(--rc-text-muted);
  flex-shrink: 0;
}

.tool-name {
  font-family: var(--rc-font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--rc-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-chevron {
  margin-left: auto;
  color: var(--rc-text-faint);
  transition: transform 0.18s ease, color 0.18s ease;

  &.rotated {
    transform: rotate(90deg);
  }
}

.tool-detail {
  border-top: 1px dashed var(--rc-line);
  padding: 10px 12px;
}

.tool-detail-block {
  display: flex;
  flex-direction: column;
  gap: 6px;

  & + .tool-detail-block {
    margin-top: 10px;
  }
}

.tool-detail-label {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--rc-text-muted);
}

.tool-json {
  margin: 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #0f172a;
  color: #c9d4f0;
  font-family: var(--rc-font-mono);
  font-size: 11.5px;
  line-height: 1.7;
  overflow: auto;
  max-height: 220px;
}
</style>
