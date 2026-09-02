<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="500px"
    append-to-body
    :close-on-click-modal="false"
    @update:model-value="emit('update:modelValue', $event)"
    @open="reason = ''"
  >
    <div class="risk-head">
      <el-icon class="risk-icon" :size="20"><WarningFilled /></el-icon>
      <p class="risk-tip">该操作会修改经费数据，确认后执行并写入审计留痕</p>
    </div>

    <div class="risk-fields">
      <div class="risk-field">
        <span>操作类型</span>
        <b>{{ operation }}</b>
      </div>
      <div class="risk-field">
        <span>目标对象</span>
        <b>{{ target }}</b>
      </div>
      <div v-if="before" class="risk-field">
        <span>变更前</span>
        <b class="pre">{{ before }}</b>
      </div>
      <div v-if="after" class="risk-field">
        <span>变更后</span>
        <b class="pre">{{ after }}</b>
      </div>
    </div>

    <el-input
      v-if="requireReason"
      v-model="reason"
      type="textarea"
      :rows="3"
      maxlength="200"
      show-word-limit
      placeholder="请填写操作原因（必填），将记入审计留痕"
    />

    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="loading" @click="handleConfirm">{{ confirmLabel }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    operation: string
    target: string
    before?: string
    after?: string
    requireReason?: boolean
    confirmLabel?: string
    loading?: boolean
  }>(),
  {
    title: '高风险操作确认',
    before: '',
    after: '',
    requireReason: true,
    confirmLabel: '确认执行',
    loading: false
  }
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'confirm', reason: string): void
}>()

const reason = ref('')

function handleConfirm() {
  if (props.requireReason && !reason.value.trim()) {
    ElMessage.warning('请填写操作原因')
    return
  }
  emit('confirm', reason.value.trim())
}
</script>

<style scoped lang="scss">
.risk-head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
}

.risk-icon {
  color: var(--rc-warning);
  flex-shrink: 0;
  margin-top: 1px;
}

.risk-tip {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--rc-text-secondary);
}

.risk-fields {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 14px 0;
  padding: 12px 14px;
  border: 1px solid var(--rc-line);
  border-radius: 8px;
  background: #fafbfe;
}

.risk-field {
  display: flex;
  align-items: flex-start;
  gap: 12px;

  span {
    width: 60px;
    flex-shrink: 0;
    font-size: 12px;
    color: var(--rc-text-muted);
    line-height: 1.7;
  }

  b {
    font-size: 13px;
    font-weight: 500;
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
}
</style>
