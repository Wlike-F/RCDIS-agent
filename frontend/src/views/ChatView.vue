<template>
  <div class="chat-page">
    <aside class="conv-panel">
      <el-button type="primary" class="new-chat-btn" @click="handleNew">
        <el-icon style="margin-right: 6px"><Plus /></el-icon>新建对话
      </el-button>
      <el-scrollbar class="conv-scroll">
        <div
          v-for="conv in chatStore.conversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: conv.id === chatStore.activeId }"
          @click="chatStore.selectConversation(conv.id)"
        >
          <el-icon class="conv-icon"><ChatDotRound /></el-icon>
          <div class="conv-meta">
            <span class="conv-title">{{ conv.title }}</span>
            <span class="conv-time num">{{ formatTimeShort(conv.updatedAt) }}</span>
          </div>
          <el-popconfirm
            title="确认删除该对话？"
            confirm-button-text="删除"
            cancel-button-text="取消"
            width="180"
            @confirm="chatStore.removeConversation(conv.id)"
          >
            <template #reference>
              <el-button class="conv-delete" text size="small" @click.stop>
                <el-icon><Delete /></el-icon>
              </el-button>
            </template>
          </el-popconfirm>
        </div>
        <div v-if="chatStore.isEmpty" class="conv-empty">暂无历史对话</div>
      </el-scrollbar>
      <p class="conv-note">对话记录保存在浏览器本地，仅用于原型演示</p>
    </aside>

    <section class="chat-main rc-card">
      <header class="chat-topbar">
        <div class="chat-provider">
          <span class="chat-provider-label">模型</span>
          <el-select
            :model-value="activeProviderId"
            class="chat-provider-select"
            placeholder="默认模型"
            :disabled="chatStore.streaming"
            @update:model-value="handleProviderChange"
          >
            <el-option
              v-for="record in providersStore.enabledRecords"
              :key="record.providerId"
              :value="record.providerId"
              :label="`${record.name} · ${record.chatModel ?? '未配置模型'}`"
            />
          </el-select>
        </div>
        <div class="chat-status">
          <span v-if="chatStore.streaming" class="status-chip streaming">
            <span class="dot pulse"></span>生成中
          </span>
          <span v-else class="status-chip idle"><span class="dot"></span>就绪</span>
        </div>
      </header>

      <div ref="scrollerRef" class="chat-scroll" @scroll="handleScroll">
        <div v-if="activeMessages.length === 0" class="chat-welcome">
          <div class="welcome-mark">
            <el-icon :size="26"><ChatDotRound /></el-icon>
          </div>
          <h3 class="welcome-title">您好，我是 RCDIS 经费管理助手</h3>
          <p class="welcome-sub">
            预算查询、支出登记、报销材料检查都可以在这里完成。<br />
            所有回答均以数据库记录为准，高风险操作会先请求确认。
          </p>
          <div class="welcome-chips">
            <button
              v-for="question in SAMPLE_QUESTIONS"
              :key="question"
              class="welcome-chip"
              type="button"
              @click="draft = question"
            >
              {{ question }}
            </button>
          </div>
        </div>

        <MessageItem
          v-for="message in activeMessages"
          :key="message.id"
          :message="message"
          @resolve="(msg, approved) => chatStore.resolveConfirmation(msg, approved)"
          @retry="chatStore.retry()"
        />
        <div ref="bottomAnchorRef" class="chat-anchor"></div>
      </div>

      <footer class="chat-input-area">
        <div class="chat-input-box">
          <el-input
            v-model="draft"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 5 }"
            placeholder="输入消息，Enter 发送，Shift + Enter 换行"
            resize="none"
            @keydown="handleKeydown"
          />
          <div class="chat-input-actions">
            <el-button
              v-if="chatStore.streaming"
              type="danger"
              plain
              circle
              title="停止生成"
              @click="chatStore.stop()"
            >
              <el-icon><VideoPause /></el-icon>
            </el-button>
            <el-button
              v-else
              type="primary"
              circle
              title="发送"
              :disabled="!draft.trim()"
              @click="handleSend"
            >
              <el-icon><Promotion /></el-icon>
            </el-button>
          </div>
        </div>
        <p class="chat-disclaimer">模型回答仅供参考，经费数据以后端数据库记录为准</p>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import MessageItem from '@/components/chat/MessageItem.vue'
import type { ChatMessage } from '@/stores/chat'
import { useChatStore } from '@/stores/chat'
import { useProvidersStore } from '@/stores/providers'
import { SAMPLE_QUESTIONS } from '@/utils/constants'
import { formatTimeShort } from '@/utils/format'

const chatStore = useChatStore()
const providersStore = useProvidersStore()

const draft = ref('')
const scrollerRef = ref<HTMLElement | null>(null)
const bottomAnchorRef = ref<HTMLElement | null>(null)
const stuck = ref(true)

const activeMessages = computed(() => chatStore.activeConversation?.messages ?? [])
const activeProviderId = computed(() => chatStore.activeConversation?.providerId ?? '')

onMounted(() => {
  void providersStore.load()
  chatStore.initActive()
})

function handleSend() {
  const text = draft.value.trim()
  if (!text || chatStore.streaming) return
  draft.value = ''
  void chatStore.send(text)
}

function handleKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

function handleNew() {
  chatStore.createConversation(chatStore.activeConversation?.providerId ?? null)
  stuck.value = true
  void scrollToBottom()
}

function handleProviderChange(providerId: string) {
  chatStore.setConversationProvider(providerId)
}

function handleScroll() {
  const el = scrollerRef.value
  if (!el) return
  stuck.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

async function scrollToBottom() {
  await nextTick()
  const el = scrollerRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(
  () => chatStore.activeConversation?.messages,
  () => {
    if (stuck.value) void scrollToBottom()
  },
  { deep: true }
)

watch(
  () => chatStore.activeId,
  () => {
    stuck.value = true
    void scrollToBottom()
  }
)
</script>

<style scoped lang="scss">
.chat-page {
  display: flex;
  gap: 16px;
  height: calc(100vh - var(--rc-topbar-height) - 52px);
  min-height: 480px;
  max-width: 1280px;
  margin: 0 auto;
}

// ---------- Conversation panel ----------
.conv-panel {
  display: flex;
  flex-direction: column;
  width: 232px;
  flex-shrink: 0;
  padding: 14px 10px 10px;
  border: 1px solid var(--rc-line);
  border-radius: var(--rc-radius-lg);
  background: var(--rc-surface);
}

.new-chat-btn {
  width: 100%;
}

.conv-scroll {
  flex: 1;
  margin-top: 12px;
}

.conv-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover {
    background: #f4f6fc;

    .conv-delete {
      opacity: 1;
    }
  }

  &.active {
    background: var(--el-color-primary-light-9);

    .conv-title {
      color: var(--rc-primary-strong);
      font-weight: 600;
    }
  }
}

.conv-icon {
  color: var(--rc-text-faint);
  flex-shrink: 0;
}

.conv-meta {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}

.conv-title {
  font-size: 13px;
  color: var(--rc-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  font-size: 10px;
  color: var(--rc-text-faint);
  margin-top: 2px;
}

.conv-delete {
  opacity: 0;
  flex-shrink: 0;
  color: var(--rc-text-muted);
  transition: opacity 0.15s ease;

  &:hover {
    color: var(--rc-danger);
  }
}

.conv-empty {
  padding: 20px 10px;
  font-size: 12px;
  color: var(--rc-text-faint);
  text-align: center;
}

.conv-note {
  margin: 8px 6px 0;
  font-size: 10.5px;
  line-height: 1.6;
  color: var(--rc-text-faint);
}

// ---------- Chat main ----------
.chat-main {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
  overflow: hidden;
}

.chat-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 20px;
  border-bottom: 1px solid var(--rc-line);
  flex-shrink: 0;
}

.chat-provider {
  display: flex;
  align-items: center;
  gap: 10px;
}

.chat-provider-label {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.chat-provider-select {
  width: 250px;
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;

  .dot {
    width: 7px;
    height: 7px;
    border-radius: 50%;
  }

  &.idle {
    background: #f2f4fa;
    color: var(--rc-text-muted);

    .dot {
      background: var(--rc-success);
    }
  }

  &.streaming {
    background: var(--el-color-primary-light-9);
    color: var(--rc-primary-strong);

    .dot {
      background: var(--rc-primary);
    }
  }
}

.pulse {
  animation: dot-pulse 1.1s ease-in-out infinite;
}

@keyframes dot-pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.3;
  }
}

.chat-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 24px 24px 8px;
}

.chat-anchor {
  height: 1px;
}

.chat-welcome {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 8vh;
  text-align: center;
}

.welcome-mark {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: linear-gradient(135deg, #2f54eb, #5e7bf7);
  color: #ffffff;
  box-shadow: 0 10px 24px rgba(47, 84, 235, 0.35);
}

.welcome-title {
  margin: 18px 0 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--rc-text);
}

.welcome-sub {
  margin: 10px 0 0;
  font-size: 13px;
  line-height: 1.9;
  color: var(--rc-text-muted);
}

.welcome-chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 10px;
  margin-top: 22px;
  max-width: 560px;
}

.welcome-chip {
  padding: 7px 14px;
  border: 1px solid var(--rc-line);
  border-radius: 999px;
  background: #ffffff;
  font-size: 12.5px;
  font-family: inherit;
  color: var(--rc-text-secondary);
  cursor: pointer;
  transition: all 0.15s ease;

  &:hover {
    border-color: var(--rc-primary);
    color: var(--rc-primary-strong);
    background: var(--el-color-primary-light-9);
    transform: translateY(-1px);
  }
}

// ---------- Input area ----------
.chat-input-area {
  flex-shrink: 0;
  padding: 14px 24px 16px;
  border-top: 1px solid var(--rc-line);
  background: #fbfcfe;
}

.chat-input-box {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.chat-input-box :deep(.el-textarea__inner) {
  padding-right: 8px;
}

.chat-input-actions {
  flex-shrink: 0;
  padding-bottom: 2px;
}

.chat-disclaimer {
  margin: 8px 2px 0;
  font-size: 11px;
  color: var(--rc-text-faint);
}

@media (max-width: 900px) {
  .conv-panel {
    display: none;
  }
}
</style>
