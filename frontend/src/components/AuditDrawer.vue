<template>
  <el-drawer :model-value="modelValue" title="审计留痕" size="460px" @update:model-value="emit('update:modelValue', $event)">
    <el-skeleton v-if="auditStore.loading" :rows="6" animated />
    <el-alert v-else-if="auditStore.error" type="error" :title="auditStore.error" :closable="false" show-icon>
      <el-button size="small" type="primary" plain @click="auditStore.load()">重试</el-button>
    </el-alert>
    <el-timeline v-else-if="auditStore.entries.length > 0" class="audit-timeline">
      <el-timeline-item
        v-for="entry in auditStore.entries"
        :key="entry.id"
        :timestamp="formatDateTime(entry.createdAt)"
        type="primary"
      >
        <div class="audit-entry">
          <div class="audit-head">
            <el-tag size="small" effect="light" type="primary">{{ auditActionLabel(entry.action) }}</el-tag>
            <span class="audit-target">
              {{ auditTargetLabel(entry.targetType) }}
              <template v-if="entry.targetId"> #{{ entry.targetId }}</template>
            </span>
          </div>
          <p v-if="entry.reason" class="audit-detail">原因：{{ entry.reason }}</p>
          <p class="audit-meta">操作人 {{ entry.actor || 'anonymous' }} · 来源 {{ entry.source }}</p>
        </div>
      </el-timeline-item>
    </el-timeline>
    <EmptyBlock v-else icon="Document" title="暂无审计记录" description="执行登记、修改、作废等操作后，留痕会显示在这里" />
  </el-drawer>
</template>

<script setup lang="ts">
import { watch } from 'vue'

import EmptyBlock from '@/components/EmptyBlock.vue'
import { useAuditStore } from '@/stores/audit'
import { auditActionLabel, auditTargetLabel } from '@/utils/constants'
import { formatDateTime } from '@/utils/format'

const props = defineProps<{ modelValue: boolean }>()

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

const auditStore = useAuditStore()

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void auditStore.load()
    }
  }
)
</script>

<style scoped lang="scss">
.audit-timeline {
  padding: 4px 8px 0 4px;
}

.audit-entry {
  .audit-head {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .audit-target {
    font-size: 13px;
    font-weight: 600;
    color: var(--rc-text);
  }

  .audit-detail {
    margin: 6px 0 0;
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--rc-text-secondary);
    word-break: break-all;
  }

  .audit-meta {
    margin: 4px 0 0;
    font-size: 12px;
    color: var(--rc-text-muted);
  }
}
</style>
