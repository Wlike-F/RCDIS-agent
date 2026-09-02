<template>
  <div class="empty-block">
    <div class="empty-icon">
      <el-icon :size="24"><component :is="icon" /></el-icon>
    </div>
    <p class="empty-title">{{ title }}</p>
    <p v-if="description" class="empty-desc">{{ description }}</p>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    icon?: string
    title: string
    description?: string
  }>(),
  {
    icon: 'Box'
  }
)
</script>

<style scoped>
.empty-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 42px 20px;
  text-align: center;
}

.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: #f2f4fa;
  color: #b3bacb;
}

.empty-title {
  margin: 14px 0 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--rc-text-secondary);
}

.empty-desc {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--rc-text-muted);
  max-width: 320px;
}

.empty-action {
  margin-top: 14px;
}
</style>
