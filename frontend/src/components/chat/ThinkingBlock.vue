<template>
  <div class="thinking" :class="{ 'thinking-active': message.thinkingActive }">
    <button class="thinking-header" type="button" @click="toggle">
      <span class="thinking-icon" :class="{ pulsing: message.thinkingActive }">
        <el-icon v-if="!message.thinkingActive"><Cpu /></el-icon>
        <el-icon v-else><Loading /></el-icon>
      </span>
      <span class="thinking-title">{{ headline }}</span>
      <span class="thinking-meta num">{{ metaLine }}</span>
      <el-icon class="thinking-caret" :class="{ expanded }">
        <ArrowDown />
      </el-icon>
    </button>

    <div v-if="expanded" class="thinking-body">
      <div class="thinking-text markdown-body" v-html="renderedContent"></div>
      <span v-if="message.thinkingActive" class="thinking-caret-inline"></span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ArrowDown, Cpu, Loading } from '@element-plus/icons-vue'

import type { ChatMessage } from '@/stores/chat'
import { renderMarkdown } from '@/utils/markdown'

const props = defineProps<{ message: ChatMessage }>()

// Headline flips based on whether the model is still emitting reasoning tokens vs. finished.
// "思考中..." keeps the "AI is working" affordance visible during the (potentially long)
// reasoning phase, so users do not perceive a stalled stream.
const headline = computed(() =>
  props.message.thinkingActive ? 'AI 正在思考…' : '已思考'
)

// Duration: measured client-side from thinkingStartedAt → thinkingEndedAt (or now if still
// active). Falls back to "0.0s" when only a partial stream arrived. Character count is the
// raw length of accumulated reasoning text.
const metaLine = computed(() => {
  const start = props.message.thinkingStartedAt
  const end = props.message.thinkingEndedAt ?? (props.message.thinkingActive ? Date.now() : null)
  const seconds =
    start !== null && end !== null ? Math.max(0, (end - start) / 1000).toFixed(1) : '0.0'
  const chars = props.message.thinkingContent.length
  return `${seconds}s · ${chars} 字`
})

const expanded = computed(() => props.message.thinkingExpanded)

const renderedContent = computed(() => renderMarkdown(props.message.thinkingContent))

// Toggling flips both the visual state and the "touched" flag: once the user explicitly acts,
// subsequent auto-collapse-on-completion is skipped so their preference sticks within the
// current session. Persistence across reloads is intentionally NOT done — thinking is
// ephemeral by product decision, pinning it to localStorage would suggest the content outlives
// the tab.
function toggle() {
  props.message.thinkingExpanded = !props.message.thinkingExpanded
  props.message.thinkingTouched = true
}
</script>

<style scoped lang="scss">
.thinking {
  margin: 4px 0 8px;
  border: 1px dashed var(--rc-line);
  border-radius: 10px;
  background: #fbfcfe;
  overflow: hidden;
  transition: border-color 0.15s ease, background 0.15s ease;

  &.thinking-active {
    border-color: var(--rc-primary);
    background: var(--el-color-primary-light-9);
  }
}

.thinking-header {
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
    background: rgba(0, 0, 0, 0.02);
  }
}

.thinking-icon {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-color-primary-light-8);
  color: var(--rc-primary-strong);
  flex-shrink: 0;

  &.pulsing {
    animation: thinking-pulse 1.4s ease-in-out infinite;
  }
}

@keyframes thinking-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(47, 84, 235, 0.35);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(47, 84, 235, 0);
  }
}

.thinking-title {
  font-size: 12.5px;
  color: var(--rc-text-secondary);
  font-weight: 500;
}

.thinking-meta {
  margin-left: auto;
  font-size: 11px;
  color: var(--rc-text-muted);
}

.thinking-caret {
  color: var(--rc-text-faint);
  transition: transform 0.15s ease;

  &.expanded {
    transform: rotate(180deg);
  }
}

.thinking-body {
  padding: 10px 12px 12px;
  border-top: 1px dashed var(--rc-line);
  background: #ffffff;
  max-height: 340px;
  overflow-y: auto;
}

.thinking-text {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--rc-text-secondary);
  white-space: normal;
  word-break: break-word;
}

// Blinking caret at the end of live reasoning so users see the stream is still active even
// when the last visible character has not changed for a moment.
.thinking-caret-inline {
  display: inline-block;
  width: 4px;
  height: 14px;
  margin-left: 3px;
  vertical-align: -2px;
  background: var(--rc-primary);
  animation: caret-blink 1s steps(2, start) infinite;
}

@keyframes caret-blink {
  to {
    visibility: hidden;
  }
}
</style>
