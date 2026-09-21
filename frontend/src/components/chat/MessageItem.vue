<template>
  <div class="message" :class="message.role">
    <template v-if="message.role === 'user'">
      <div class="bubble-user">
        <div v-if="message.attachments && message.attachments.length" class="user-attachments">
          <div v-for="att in message.attachments" :key="att.id" class="user-att">
            <AuthenticatedImage
              v-if="isImageAttachment(att)"
              :src="att.url"
              :preview="true"
              fit="cover"
              class="user-att-preview"
            />
            <span class="user-att-chip"><el-icon><Paperclip /></el-icon>{{ att.name }}</span>
          </div>
        </div>
        <span class="user-text">{{ message.content }}</span>
      </div>
    </template>

    <template v-else>
      <div class="assistant-avatar" :class="'state-' + avatarState">
        <img :src="avatarUrl" alt="RCDIS Agent" class="avatar-img" />
        <span v-if="avatarState === 'thinking'" class="ring ring-thinking"></span>
        <span v-else-if="avatarState === 'tool'" class="ring ring-tool"></span>
        <span v-else-if="avatarState === 'confirm'" class="badge badge-confirm">
          <el-icon><WarningFilled /></el-icon>
        </span>
        <span v-else-if="avatarState === 'error'" class="badge badge-error">
          <el-icon><CircleCloseFilled /></el-icon>
        </span>
        <span v-else-if="message.content" class="badge badge-done">
          <el-icon><Check /></el-icon>
        </span>
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
          <template v-if="message.content">
            <div class="msg-text markdown-body" v-html="renderedContent"></div>
            <span v-if="message.status === 'streaming'" class="stream-caret"></span>
          </template>
          <div v-else-if="message.status === 'streaming'" class="thinking">
            <span class="thinking-dots"><i></i><i></i><i></i></span>
            <span class="thinking-text">{{ thinkingLabel }}</span>
          </div>
        </div>

        <div
          v-else-if="!message.error && !message.confirmation && message.toolCalls.length === 0"
          class="message-empty"
        >
          该回复未返回可显示内容，请重新生成
        </div>

        <ConfirmationCard
          v-if="message.confirmation"
          :confirmation="message.confirmation"
          @resolve="(approved: boolean) => $emit('resolve', message, approved)"
        />

        <AgentTaskCard v-if="message.confirmation?.task" :task="message.confirmation.task" />
        <el-alert
          v-else-if="message.confirmation?.taskLoadError"
          type="warning"
          :title="`任务状态暂时无法同步：${message.confirmation.taskLoadError}`"
          :closable="false"
          show-icon
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

import avatarUrl from '@/assets/agent-avatar.png'
import AuthenticatedImage from '@/components/AuthenticatedImage.vue'
import type { ChatMessage, MessageAttachment } from '@/stores/chat'
import { formatTimeShort } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'

import ConfirmationCard from './ConfirmationCard.vue'
import AgentTaskCard from './AgentTaskCard.vue'
import ToolCallRow from './ToolCallRow.vue'

const props = defineProps<{ message: ChatMessage }>()

defineEmits<{
  (e: 'resolve', message: ChatMessage, approved: boolean): void
  (e: 'retry'): void
}>()

// Full GFM markdown (tables / headings / lists / code fences) parsed + sanitized in utils/markdown.
const renderedContent = computed(() => renderMarkdown(props.message.content))

// While waiting for the first token (model TTFT can take seconds), show a friendly
// "thinking" indicator instead of an empty bubble with a lone blinking caret.
const thinkingLabel = computed(() =>
  props.message.toolCalls.some((t) => t.status === 'running') ? '正在调用工具…' : '正在思考…'
)

const shortTime = computed(() => formatTimeShort(props.message.time))

function isImageAttachment(attachment: MessageAttachment): boolean {
  return attachment.kind.toUpperCase() === 'IMAGE' || /\.(png|jpe?g|gif|webp|bmp)$/i.test(attachment.name)
}

// Drives the avatar state-ring: what the agent is doing right now.
const avatarState = computed(() => {
  if (props.message.confirmation && !props.message.confirmation.resolved) return 'confirm'
  if (props.message.status === 'streaming') {
    return props.message.toolCalls.some((t) => t.status === 'running') ? 'tool' : 'thinking'
  }
  if (props.message.status === 'error') return 'error'
  return 'done'
})
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

.user-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

.user-att { display: flex; flex-direction: column; align-items: flex-start; gap: 5px; }
.user-att-preview { width: 96px; height: 72px; border-radius: 7px; overflow: hidden; }

.user-att-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  font-size: 11.5px;
}

.assistant-avatar {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  flex-shrink: 0;
  margin-top: 2px;
}

.avatar-img {
  width: 30px;
  height: 30px;
  border-radius: 9px;
  object-fit: cover;
  display: block;
}

.ring {
  position: absolute;
  inset: -3px;
  border-radius: 12px;
  pointer-events: none;
}

.ring-thinking {
  border: 2px solid rgba(47, 84, 235, 0.55);
  animation: ring-pulse 1.2s ease-in-out infinite;
}

.ring-tool {
  border: 2px dashed rgba(47, 84, 235, 0.7);
  animation: ring-spin 2.4s linear infinite;
}

@keyframes ring-pulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.9;
  }
  50% {
    transform: scale(1.12);
    opacity: 0.4;
  }
}

@keyframes ring-spin {
  to {
    transform: rotate(360deg);
  }
}

.badge {
  position: absolute;
  right: -5px;
  bottom: -5px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  color: #ffffff;
  border: 1.5px solid #ffffff;

  .el-icon {
    font-size: 11px;
  }
}

.badge-confirm {
  background: var(--rc-warning, #d97706);
}

.badge-error {
  background: var(--rc-danger, #dc2626);
}

.badge-done {
  background: var(--rc-success, #16a34a);
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

// v-html output is not covered by scoped styles, so target it through :deep().
.markdown-body {
  :deep(p) {
    margin: 6px 0;
  }

  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4) {
    margin: 12px 0 6px;
    font-weight: 600;
    line-height: 1.4;
  }

  :deep(h1) {
    font-size: 17px;
  }

  :deep(h2) {
    font-size: 16px;
  }

  :deep(h3) {
    font-size: 15px;
  }

  :deep(h4) {
    font-size: 14px;
  }

  :deep(hr) {
    border: none;
    border-top: 1px solid var(--rc-line);
    margin: 12px 0;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 20px;
    margin: 6px 0;
  }

  :deep(li) {
    margin: 2px 0;
  }

  :deep(blockquote) {
    border-left: 3px solid var(--rc-line);
    padding-left: 10px;
    color: var(--rc-text-secondary);
    margin: 8px 0;
  }

  :deep(table) {
    display: block;
    width: 100%;
    overflow-x: auto;
    border-collapse: collapse;
    margin: 10px 0;
  }

  :deep(th),
  :deep(td) {
    border: 1px solid var(--rc-line);
    padding: 6px 10px;
    font-size: 13px;
    text-align: left;
    vertical-align: top;
  }

  :deep(th) {
    background: #f4f6fc;
    font-weight: 600;
    white-space: nowrap;
  }

  :deep(pre) {
    background: #0f172a;
    color: #e2e8f0;
    padding: 10px 12px;
    border-radius: 8px;
    overflow-x: auto;
    margin: 8px 0;
  }

  :deep(pre code) {
    background: transparent;
    color: inherit;
    padding: 0;
    font-size: 12.5px;
  }

  :deep(:not(pre) > code) {
    background: #eef1f8;
    color: var(--rc-primary-strong);
    padding: 1px 5px;
    border-radius: 4px;
    font-size: 12.5px;
  }

  :deep(strong) {
    font-weight: 600;
    color: var(--rc-text);
  }
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

.thinking {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
}

.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;

  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--rc-primary);
    animation: dot-bounce 1.2s ease-in-out infinite;

    &:nth-child(2) {
      animation-delay: 0.15s;
    }

    &:nth-child(3) {
      animation-delay: 0.3s;
    }
  }
}

.thinking-text {
  font-size: 12.5px;
  color: var(--rc-text-muted);
}

@keyframes dot-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }
  30% {
    transform: translateY(-4px);
    opacity: 1;
  }
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

.message-empty {
  padding: 9px 12px;
  border: 1px dashed var(--rc-line);
  border-radius: 8px;
  color: var(--rc-text-muted);
  font-size: 12.5px;
}

.message-meta {
  display: flex;
  gap: 10px;
  font-size: 11px;
  color: var(--rc-text-faint);
}
</style>
