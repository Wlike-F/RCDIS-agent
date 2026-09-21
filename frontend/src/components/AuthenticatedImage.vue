<template>
  <div class="authenticated-image">
    <el-image
      v-if="objectUrl"
      v-bind="$attrs"
      :src="objectUrl"
      :preview-src-list="preview ? [objectUrl] : []"
      :preview-teleported="preview"
    />
    <div v-else-if="loading" class="image-state" aria-label="图片加载中">
      <el-icon class="is-loading"><Loading /></el-icon>
    </div>
    <el-tooltip v-else :content="errorMessage" placement="top">
      <div class="image-state failed" aria-label="图片加载失败">
        <el-icon><PictureFilled /></el-icon>
      </div>
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

import { API_BASE_URL } from '@/api/client'
import { getAccessToken } from '@/utils/authToken'

defineOptions({ inheritAttrs: false })

const props = defineProps<{ src: string; preview: boolean }>()

const objectUrl = ref('')
const loading = ref(false)
const errorMessage = ref('图片加载失败')
let controller: AbortController | null = null

function releaseObjectUrl(): void {
  if (objectUrl.value.startsWith('blob:')) URL.revokeObjectURL(objectUrl.value)
  objectUrl.value = ''
}

function resourceUrl(source: string): string {
  if (/^https?:\/\//i.test(source)) return source
  const normalized = source.startsWith('/') ? source : `/${source}`
  return `${API_BASE_URL}${normalized}`
}

function isProtectedResource(source: string): boolean {
  if (!/^https?:\/\//i.test(source)) return true
  const resourceOrigin = new URL(source, window.location.href).origin
  const apiOrigin = new URL(API_BASE_URL || window.location.origin, window.location.href).origin
  return resourceOrigin === apiOrigin
}

async function load(source: string): Promise<void> {
  controller?.abort()
  controller = null
  releaseObjectUrl()
  if (!source) return
  if (!isProtectedResource(source)) {
    objectUrl.value = source
    return
  }

  controller = new AbortController()
  loading.value = true
  errorMessage.value = '图片加载失败'
  const headers: Record<string, string> = {}
  const token = getAccessToken()
  if (token) headers.Authorization = `Bearer ${token}`

  try {
    const response = await fetch(resourceUrl(source), { headers, signal: controller.signal })
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const blob = await response.blob()
    if (!blob.type.startsWith('image/')) throw new Error('资源不是可预览的图片')
    objectUrl.value = URL.createObjectURL(blob)
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') return
    errorMessage.value = error instanceof Error ? error.message : '图片加载失败'
  } finally {
    loading.value = false
  }
}

watch(() => props.src, (source) => void load(source), { immediate: true })

onBeforeUnmount(() => {
  controller?.abort()
  releaseObjectUrl()
})
</script>

<style scoped lang="scss">
.authenticated-image { display: inline-flex; max-width: 100%; }
.authenticated-image :deep(.el-image) { width: 100%; height: 100%; }
.image-state { display: grid; place-items: center; width: 100%; min-width: 40px; min-height: 40px; border-radius: 6px; background: #f2f4f8; color: var(--rc-text-muted); }
.image-state.failed { color: var(--rc-danger); }
</style>
