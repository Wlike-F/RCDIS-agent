<template>
  <div class="stat-card rc-card">
    <div class="stat-icon" :class="tone">
      <el-icon :size="20"><component :is="icon" /></el-icon>
    </div>
    <div class="stat-body">
      <span class="stat-label">{{ label }}</span>
      <div class="stat-value-row">
        <span class="stat-value num"><slot>{{ value }}</slot></span>
        <span v-if="unit" class="stat-unit">{{ unit }}</span>
      </div>
      <span v-if="sub" class="stat-sub">{{ sub }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    icon: string
    label: string
    value?: string | number
    unit?: string
    sub?: string
    tone?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral'
  }>(),
  {
    tone: 'primary',
    value: '',
    unit: '',
    sub: ''
  }
)
</script>

<style scoped>
.stat-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 20px;
  transition: transform 0.18s ease, box-shadow 0.18s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--rc-shadow-pop);
}

.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: 10px;
  flex-shrink: 0;
}

.stat-icon.primary {
  background: #ebeffc;
  color: #2f54eb;
}

.stat-icon.success {
  background: #e7f6e9;
  color: #16a34a;
}

.stat-icon.warning {
  background: #fcf2e5;
  color: #d97706;
}

.stat-icon.danger {
  background: #fce4e4;
  color: #dc2626;
}

.stat-icon.neutral {
  background: #eef1f7;
  color: #64748b;
}

.stat-body {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.stat-label {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.stat-value-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
  margin-top: 6px;
}

.stat-value {
  font-size: 23px;
  font-weight: 600;
  color: var(--rc-text);
  line-height: 1.1;
}

.stat-unit {
  font-size: 12px;
  color: var(--rc-text-muted);
}

.stat-sub {
  margin-top: 6px;
  font-size: 12px;
  color: var(--rc-text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 220px;
}
</style>
