<template>
  <div class="message" :class="message.role">
    <template v-if="message.role === 'user'">
      <div class="bubble-user">{{ message.content }}</div>
    </template>

    <template v-else>
      <div class="assistant-avatar">
        <el-icon :size="14"><Cpu /></el-icon>
      </div>
      <div class="assistant-body">
        <template v-if="message.toolCalls.length">
          <ToolCallRow
            v-for="tool in message.toolCalls"
            :key="tool.id"
            :tool="tool"
            class="message-tool"
          />
        </template>

        <div v-if="message.content || message.status === 'streaming'" class="bubble-assistant">
          <span class="msg-text" v-html="renderedContent"></span>
          <span v-if="message.status === 'streaming'" class="stream-caret"></span>
        </div>

        <ConfirmationCard
          v-if="message.confirmation"
          :confirmation="message.confirmation"
          @resolve="(approved: boolean) => $emit('resolve', message, approved)"
        />

        <div v-if="message.error" class="message-error">
          <el-alert type="error" :title="message.error" :closable="false" show-icon />
          <el-button size="small" text type="primary" @click="$emit('retry')">重新生成</el-button>
        </div>

        <div v-if="message.stopped" class="message-stopped">已停止生成本条回复</div>

        <div v-if="message.status !== 'streaming' && (message.modelName || message.time)" class="message-meta">
          <span v-if="message.modelName" class="num">{{ message.providerId }} · {{ message.modelName }}</span>
          <span class="num">{{ shortTime }}</span>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

import type { ChatMessage } from '@/stores/chat'
import { formatTimeShort } from '@/utils/format'

import ConfirmationCard from './ConfirmationCard.vue'
import ToolCallRow from './ToolCallRow.vue'

const props = defineProps<{ message: ChatMessage }>()

defineEmits<{
  (e: 'resolve', message: ChatMessage, approved: boolean): void
  (e: 'retry'): void
}>()

// Minimal safe rendering: escape first, then allow bold / inline code / line breaks
function escapeHtml(input: string): string {
  return input.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

const renderedContent = computed(() => {
  const escaped = escapeHtml(props.message.content)
  return escaped
    .replace(/`([^`]+)`/g, '<code class="inline-code">$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br>')
})

const shortTime = computed(() => formatTimeShort(props.message.time))
</script>

<style scoped lang="scss">
.message {
  display: flex;
  margin-bottom: 20px;

  &.user {
    justify-content: flex-end;
  }
}

.bubble-user {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: 14px 14px 4px 14px;
  background: linear-gradient(135deg, #2f54eb, #3d63f0);
  color: #ffffff;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  box-shadow: 0 4px 12px rgba(47, 84, 235, 0.25);
}

.assistant-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  background: linear-gradient(135deg, #2f54eb, #5e7bf7);
  color: #ffffff;
  flex-shrink: 0;
  margin-top: 2px;
}

.assistant-body {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 10px;
  flex: 1;
  min-width: 0;
  margin-left: 12px;
}

.message-tool {
  width: 100%;
}

.bubble-assistant {
  max-width: 100%;
  padding: 12px 16px;
  border: 1px solid var(--rc-line);
  border-radius: 4px 14px 14px 14px;
  background: var(--rc-surface);
  box-shadow: var(--rc-shadow-card);
  font-size: 14px;
  line-height: 1.8;
  word-break: break-word;
}

.msg-text {
  color: var(--rc-text);
}

.stream-caret {
  display: inline-block;
  width: 2px;
  height: 15px;
  margin-left: 3px;
  vertical-align: text-bottom;
  background: var(--rc-primary);
  animation: caret-blink 1s steps(2) infinite;
}

@keyframes caret-blink {
  0%,
  49% {
    opacity: 1;
  }
  50%,
  100% {
    opacity: 0;
  }
}

.message-error {
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.message-stopped {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.message-meta {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: var(--rc-text-faint);
}
</style>
