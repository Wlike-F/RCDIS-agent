<template>
  <div class="page-header">
    <div class="page-header-main">
      <span v-if="kicker" class="kicker">{{ kicker }}</span>
      <h2 class="page-title">{{ title }}</h2>
      <p v-if="description" class="page-desc">{{ description }}</p>
    </div>
    <div class="page-header-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  kicker?: string
  description?: string
}>()
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}

.page-title {
  margin: 6px 0 0;
  font-size: 20px;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: var(--rc-text);
}

.page-desc {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--rc-text-muted);
}

.page-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-bottom: 2px;
}
</style>
