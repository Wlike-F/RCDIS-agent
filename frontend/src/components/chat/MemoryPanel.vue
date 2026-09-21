<template>
  <div class="memory-panel">
    <div class="memory-head">
      <div>
        <span class="kicker">SEMANTIC MEMORY</span>
        <h3>跨会话记忆</h3>
      </div>
      <el-button size="small" :loading="loading" @click="reload">
        <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新
      </el-button>
    </div>

    <el-alert
      v-if="error"
      type="error"
      :title="error"
      :closable="false"
      show-icon
      class="memory-alert"
    />

    <div v-if="setting" class="switch-card">
      <div class="switch-row">
        <div class="switch-label">
          <b>记忆抽取（写）</b>
          <span class="switch-desc">轮末异步从对话中抽取长期事实存入记忆库</span>
        </div>
        <el-switch
          :model-value="setting.extractEnabled"
          :disabled="!setting.globalExtractEnabled"
          @change="(v: boolean) => onToggle(v, setting!.injectEnabled)"
        />
      </div>
      <div class="switch-row">
        <div class="switch-label">
          <b>记忆注入（读）</b>
          <span class="switch-desc">新会话开始时把记忆注入提示词前缀</span>
        </div>
        <el-switch
          :model-value="setting.injectEnabled"
          :disabled="!setting.globalInjectEnabled"
          @change="(v: boolean) => onToggle(setting!.extractEnabled, v)"
        />
      </div>
      <p v-if="!setting.globalExtractEnabled || !setting.globalInjectEnabled" class="global-note">
        全局开关已关闭（管理员配置），个人开关暂不可用。
      </p>
    </div>

    <div class="list-card">
      <span class="facts-label">记忆库（{{ memories.length }} 条）</span>
      <el-empty v-if="!memories.length" description="暂无跨会话记忆" :image-size="60" />
      <div v-else class="memory-list">
        <div v-for="m in memories" :key="m.id" class="memory-item">
          <el-tag size="small" effect="plain" :type="factTag(m.factType)">{{ m.factType || 'fact' }}</el-tag>
          <span class="memory-content">{{ m.content }}</span>
          <span class="memory-meta num">命中 {{ m.hitCount ?? 0 }} 次</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { api } from '@/api'
import type { MemorySettingVO, SemanticMemoryVO } from '@/api/types'

const setting = ref<MemorySettingVO | null>(null)
const memories = ref<SemanticMemoryVO[]>([])
const loading = ref(false)
const error = ref('')

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const [s, list] = await Promise.all([api.getMemorySetting(), api.listSemanticMemories()])
    setting.value = s
    memories.value = list
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载记忆失败'
  } finally {
    loading.value = false
  }
}

async function onToggle(extract: boolean, inject: boolean) {
  try {
    setting.value = await api.updateMemorySetting(extract, inject)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '更新开关失败'
  }
}

function factTag(type: string | null): 'primary' | 'success' | 'info' | 'warning' | 'danger' {
  switch (type) {
    case 'preference':
      return 'success'
    case 'identity':
      return 'primary'
    case 'constraint':
      return 'warning'
    case 'decision':
      return 'primary'
    default:
      return 'info'
  }
}

onMounted(reload)
</script>

<style scoped lang="scss">
.memory-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.memory-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.switch-card,
.list-card {
  padding: 12px 14px;
  border: 1px solid var(--rc-line);
  border-radius: 10px;
  background: #fbfcfe;
}

.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 0;

  & + .switch-row {
    border-top: 1px dashed var(--rc-line);
  }
}

.switch-label {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.switch-desc {
  font-size: 11.5px;
  color: var(--rc-text-faint);
}

.global-note {
  margin: 6px 0 0;
  font-size: 11.5px;
  color: var(--rc-warning, #d97706);
}

.facts-label {
  display: block;
  margin-bottom: 8px;
  font-size: 11px;
  color: var(--rc-text-muted);
}

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.memory-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.memory-content {
  flex: 1;
  font-size: 12.5px;
  color: var(--rc-text-secondary);
}

.memory-meta {
  font-size: 11px;
  color: var(--rc-text-faint);
}
</style>
