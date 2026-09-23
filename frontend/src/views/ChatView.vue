<template>
  <div class="chat-page">
    <aside class="conv-panel">
      <el-button type="primary" class="new-chat-btn" @click="handleNew">
        <el-icon style="margin-right: 6px"><Plus /></el-icon>新建对话
      </el-button>
      <el-scrollbar class="conv-scroll">
        <div
          v-for="conv in chatStore.orderedConversations"
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
      <p class="conv-note">会话消息已持久化到服务端数据库，换设备登录后同样可见</p>
    </aside>

    <section class="chat-main rc-card">
      <el-tabs v-model="mainTab" class="main-tabs">
        <el-tab-pane label="对话" name="chat">
      <div class="chat-pane">
      <header class="chat-topbar">
        <div class="chat-provider">
          <span class="chat-provider-label">模型</span>
          <el-select
            :model-value="displayProviderId"
            class="chat-provider-select"
            placeholder="默认模型"
            :disabled="chatStore.streaming"
            @update:model-value="handleProviderChange"
          >
            <el-option
              v-for="record in providersStore.chatOptions"
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

      <div ref="scrollerRef" class="chat-scroll" @scroll="handleScroll" @mouseup="onMouseUp">
        <div v-if="activeMessages.length === 0" class="chat-welcome">
          <div class="welcome-mark">
            <el-icon :size="26"><ChatDotRound /></el-icon>
          </div>
          <h3 class="welcome-title">您好，我是 RCDIS 经费管理助手</h3>
          <p class="welcome-sub">
            预算查询、支出登记、报销材料检查都可以在这里完成。<br />
            所有回答均以数据库记录为准，高风险操作会先请求确认。
          </p>
          <div class="welcome-actions">
            <button
              v-for="action in QUICK_ACTIONS"
              :key="action.key"
              class="welcome-action"
              type="button"
              @click="draft = action.prompt"
            >
              <span class="welcome-action-icon">
                <el-icon :size="18"><component :is="action.icon" /></el-icon>
              </span>
              <span class="welcome-action-body">
                <span class="welcome-action-head">
                  <span class="welcome-action-title">{{ action.title }}</span>
                  <span class="welcome-action-tag">{{ action.category }}</span>
                </span>
                <span class="welcome-action-desc">{{ action.desc }}</span>
              </span>
            </button>
          </div>
        </div>

        <MessageItem
          v-for="message in activeMessages"
          :key="message.id"
          :message="message"
          @resolve="(msg, approved) => chatStore.resolveConfirmation(activeConversationId, msg, approved)"
          @retry="chatStore.retry()"
        />
        <div ref="bottomAnchorRef" class="chat-anchor"></div>
      </div>

      <footer class="chat-input-area">
        <div class="composer">
          <div v-if="quoteText" class="quote-bar">
            <el-icon class="quote-icon"><ChatLineSquare /></el-icon>
            <span class="quote-text">{{ quoteText }}</span>
            <el-button text size="small" title="移除引用" @click="quoteText = ''">
              <el-icon><Close /></el-icon>
            </el-button>
          </div>

          <div v-if="pendingAttachments.length" class="attach-bar">
            <div
              v-for="att in pendingAttachments"
              :key="att.id"
              class="attach-card"
            >
              <button class="attach-remove" type="button" title="移除附件" @click="removeAttachment(att.id)">
                <el-icon><Close /></el-icon>
              </button>
              <AuthenticatedImage
                v-if="isImageAttachment(att)"
                :src="att.url"
                :preview="true"
                fit="cover"
                class="attach-image"
              />
              <div v-else class="attach-file">
                <el-icon><Paperclip /></el-icon>
                <span class="attach-name" :title="att.originalName">{{ att.originalName }}</span>
              </div>
              <div class="attach-caption">
                <span class="attach-name" :title="att.originalName">{{ att.originalName }}</span>
                <span class="attach-kind num">{{ att.kind }}</span>
              </div>
            </div>
          </div>

          <el-input
            v-model="draft"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            placeholder="输入消息，Enter 发送，Shift + Enter 换行；可直接粘贴截图；选中回复文本可点击“询问”引用提问"
            resize="none"
            class="composer-input"
            @keydown="handleKeydown"
            @paste="onPaste"
          />

          <div class="composer-footer">
            <div class="composer-left">
              <el-tooltip content="上传附件（pdf / docx / pptx / txt / 图片）" placement="top">
                <el-button circle :loading="uploading" @click="triggerAttach">
                  <el-icon v-if="!uploading"><Paperclip /></el-icon>
                </el-button>
              </el-tooltip>
              <input
                ref="fileInputRef"
                type="file"
                hidden
                accept=".txt,.md,.pdf,.docx,.pptx,.png,.jpg,.jpeg,.gif,.webp"
                @change="onFileChange"
              />
              <span class="composer-hint">Enter 发送 · Shift+Enter 换行 · 支持粘贴或上传图片与附件</span>
            </div>
            <div class="composer-right">
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
                :disabled="!draft.trim() && !quoteText"
                @click="handleSend"
              >
                <el-icon><Promotion /></el-icon>
              </el-button>
            </div>
          </div>
        </div>
        <p class="chat-disclaimer">模型回答仅供参考，经费数据以后端数据库记录为准</p>
      </footer>
      </div>
        </el-tab-pane>
        <el-tab-pane label="上下文轨迹" name="trace">
          <div class="trace-pane">
            <TracePanel />
          </div>
        </el-tab-pane>
        <el-tab-pane label="记忆" name="memory">
          <div class="trace-pane">
            <MemoryPanel />
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <transition name="fade">
      <div v-if="selBar.visible" class="selection-bar" :style="{ left: selBar.x + 'px', top: selBar.y + 'px' }">
        <el-button size="small" type="primary" @click="askSelection">
          <el-icon style="margin-right: 4px"><ChatLineSquare /></el-icon>询问
        </el-button>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'

import { api } from '@/api'
import { toApiError } from '@/api/client'
import MessageItem from '@/components/chat/MessageItem.vue'
import MemoryPanel from '@/components/chat/MemoryPanel.vue'
import TracePanel from '@/components/chat/TracePanel.vue'
import AuthenticatedImage from '@/components/AuthenticatedImage.vue'
import type { AgentAttachmentVO } from '@/api/types'
import type { ChatMessage, MessageAttachment } from '@/stores/chat'
import { useChatStore } from '@/stores/chat'
import { useProvidersStore } from '@/stores/providers'
import { QUICK_ACTIONS } from '@/utils/constants'
import { formatTimeShort } from '@/utils/format'

const chatStore = useChatStore()
const providersStore = useProvidersStore()
const route = useRoute()

const draft = ref('')
const mainTab = ref<'chat' | 'trace' | 'memory'>('chat')
const scrollerRef = ref<HTMLElement | null>(null)
const bottomAnchorRef = ref<HTMLElement | null>(null)
const stuck = ref(true)

// Attachments staged for the next message, plus the quoted selection ("ask about this").
const pendingAttachments = ref<AgentAttachmentVO[]>([])
const uploading = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)
const quoteText = ref('')
const selectionText = ref('')
const selBar = reactive({ visible: false, x: 0, y: 0 })

const activeMessages = computed(() => chatStore.activeConversation?.messages ?? [])
const activeConversationId = computed(() => chatStore.activeConversation?.id ?? '')
const activeProviderId = computed(() => chatStore.activeConversation?.providerId ?? '')
// Echo the default model even before an explicit pick, so non-admin users see a real selection.
const displayProviderId = computed(
  () => activeProviderId.value || providersStore.defaultChatOption?.providerId || ''
)

onMounted(() => {
  void providersStore.loadForChat()
  chatStore.initActive()
  const ask = route.query.ask
  if (typeof ask === 'string' && ask) {
    draft.value = ask
    mainTab.value = 'chat'
  }
})

function composeText(): string {
  const question = draft.value.trim()
  if (quoteText.value) {
    return `【引用内容】\n"""\n${quoteText.value}\n"""\n\n【我的问题】\n${question}`
  }
  return question
}

function handleSend() {
  const text = composeText()
  if (!text || chatStore.streaming) return
  const attachments: MessageAttachment[] = pendingAttachments.value.map((a) => ({
    id: a.id,
    name: a.originalName,
    kind: a.kind,
    url: a.url
  }))
  const attachmentIds = pendingAttachments.value.map((a) => a.id)
  draft.value = ''
  quoteText.value = ''
  pendingAttachments.value = []
  void chatStore.send(text, { attachmentIds, attachments })
}

function triggerAttach() {
  fileInputRef.value?.click()
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await uploadFiles([file])
}

/** Paste-to-upload: image files on the clipboard bypass the textarea and stage as attachments. */
function onPaste(event: ClipboardEvent) {
  const files = event.clipboardData?.files
  if (!files || files.length === 0) return
  const images = Array.from(files).filter((f) => f.type.startsWith('image/'))
  if (images.length === 0) return
  // Keep the binary blob out of the textarea; only the staged thumbnail represents it.
  event.preventDefault()
  void uploadFiles(images)
}

async function uploadFiles(files: File[]) {
  uploading.value = true
  try {
    for (const file of files) {
      try {
        const vo = await api.uploadAgentAttachment(file, chatStore.activeConversation?.id ?? undefined)
        pendingAttachments.value.push(vo)
        ElMessage.success(`已上传 ${vo.originalName}（${vo.kind}）`)
      } catch (error) {
        ElMessage.error(toApiError(error).message)
      }
    }
  } finally {
    uploading.value = false
  }
}

function isImageAttachment(att: AgentAttachmentVO): boolean {
  return att.kind.toUpperCase() === 'IMAGE' || /\.(png|jpe?g|gif|webp|bmp)$/i.test(att.originalName)
}

function removeAttachment(id: number) {
  pendingAttachments.value = pendingAttachments.value.filter((a) => a.id !== id)
}

function onMouseUp(event: MouseEvent) {
  const selection = window.getSelection()
  const text = selection ? selection.toString().trim() : ''
  if (text && text.length <= 2000) {
    selectionText.value = text
    selBar.x = Math.max(8, Math.min(event.clientX - 40, window.innerWidth - 120))
    selBar.y = Math.max(60, event.clientY - 48)
    selBar.visible = true
  } else {
    selBar.visible = false
  }
}

function askSelection() {
  quoteText.value = selectionText.value
  selBar.visible = false
  window.getSelection()?.removeAllRanges()
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
  selBar.visible = false
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
  max-width: 1560px;
  margin: 0 auto;
  padding: 0 16px;
}

// ---------- Conversation panel ----------
.conv-panel {
  display: flex;
  flex-direction: column;
  width: 248px;
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

.main-tabs {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;

  :deep(.el-tabs__header) {
    margin: 0 20px;
  }

  :deep(.el-tabs__content) {
    flex: 1;
    min-height: 0;
  }

  :deep(.el-tab-pane) {
    height: 100%;
  }
}

.chat-pane {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.trace-pane {
  height: 100%;
  overflow-y: auto;
  padding: 16px 20px 24px;
  // 轨迹/记忆内容限宽居中：避免拉宽页面后行文过长
  max-width: 1160px;
  margin: 0 auto;
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

.welcome-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin-top: 24px;
  width: 100%;
  max-width: 560px;

  @media (max-width: 640px) {
    grid-template-columns: 1fr;
  }
}

.welcome-action {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px;
  text-align: left;
  border: 1px solid var(--rc-line);
  border-radius: var(--rc-radius);
  background: var(--rc-surface);
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;

  &:hover {
    border-color: var(--rc-primary);
    box-shadow: var(--rc-shadow-card);
    transform: translateY(-2px);

    .welcome-action-icon {
      background: var(--rc-primary);
      color: #ffffff;
    }
  }
}

.welcome-action-icon {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--rc-primary-strong);
  transition: background 0.15s ease, color 0.15s ease;
}

.welcome-action-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.welcome-action-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.welcome-action-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--rc-text);
}

.welcome-action-tag {
  flex-shrink: 0;
  padding: 1px 7px;
  border-radius: 999px;
  background: #f2f4fa;
  font-size: 10.5px;
  color: var(--rc-text-muted);
}

.welcome-action-desc {
  font-size: 12px;
  line-height: 1.5;
  color: var(--rc-text-muted);
}

// ---------- Input area ----------
.chat-input-area {
  flex-shrink: 0;
  padding: 14px 24px 16px;
  border-top: 1px solid var(--rc-line);
  background: linear-gradient(180deg, #fbfcfe 0%, #f6f8fc 100%);
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--rc-line);
  border-radius: 16px;
  background: #ffffff;
  box-shadow: var(--rc-shadow-card);
  transition: border-color 0.15s ease, box-shadow 0.15s ease;

  &:focus-within {
    border-color: var(--rc-primary);
    box-shadow: 0 0 0 3px rgba(47, 84, 235, 0.08);
  }
}

.composer-input :deep(.el-textarea__inner) {
  border: none;
  box-shadow: none;
  background: transparent;
  padding: 2px 4px;
  font-size: 13.5px;
}

.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.composer-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.composer-hint {
  font-size: 11px;
  color: var(--rc-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.composer-right {
  flex-shrink: 0;
}

.quote-bar {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border-left: 3px solid var(--rc-primary);
  border-radius: 6px;
  background: #f2f5ff;
}

.quote-icon {
  margin-top: 2px;
  color: var(--rc-primary);
  flex-shrink: 0;
}

.quote-text {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--rc-text-secondary);
  white-space: pre-wrap;
  max-height: 72px;
  overflow-y: auto;
}

.attach-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.attach-card {
  position: relative;
  width: 132px;
  padding: 6px;
  border: 1px solid var(--rc-line);
  border-radius: 10px;
  background: #ffffff;

  &:hover {
    border-color: var(--rc-primary);
  }
}

.attach-image {
  width: 100%;
  height: 64px;
  border-radius: 6px;
  overflow: hidden;
  background: #f2f4f8;
}

.attach-file {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 40px;
  padding: 0 2px;
  color: var(--rc-text-muted);
}

.attach-caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  margin-top: 5px;
}

.attach-name {
  min-width: 0;
  font-size: 11px;
  color: var(--rc-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.attach-kind {
  flex-shrink: 0;
  font-size: 10px;
  color: var(--rc-text-faint);
}

.attach-remove {
  position: absolute;
  top: -7px;
  right: -7px;
  z-index: 1;
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 1px solid var(--rc-line);
  border-radius: 50%;
  background: #ffffff;
  color: var(--rc-text-muted);
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.1);
  transition: all 0.15s ease;

  &:hover {
    color: var(--rc-danger);
    border-color: var(--rc-danger);
  }
}

.selection-bar {
  position: fixed;
  z-index: 2000;
  padding: 4px;
  border-radius: 8px;
  background: #ffffff;
  border: 1px solid var(--rc-line);
  box-shadow: var(--rc-shadow-pop);
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
