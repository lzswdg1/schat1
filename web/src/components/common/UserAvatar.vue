<template>
  <img
    v-if="blobUrl"
    class="user-avatar"
    :src="blobUrl"
    :style="avatarStyle"
    alt=""
    @error="handleError"
  />
  <div
    v-else
    class="user-avatar user-avatar--placeholder"
    :style="avatarStyle"
  >
    <svg viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
      <circle cx="12" cy="8" r="4" fill="currentColor" />
      <path d="M4 20c0-3.3 3.6-6 8-6s8 2.7 8 6" fill="currentColor" />
    </svg>
  </div>
</template>

<script setup>
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import http from '@/api/http.js'
import { USE_MOCK } from '@/api/provider.js'

const props = defineProps({
  src: {
    type: String,
    default: ''
  },
  size: {
    type: Number,
    default: 40
  }
})

const blobUrl = ref('')

const avatarStyle = computed(() => ({
  width: `${props.size}px`,
  height: `${props.size}px`
}))

function revoke() {
  if (blobUrl.value?.startsWith('blob:')) {
    URL.revokeObjectURL(blobUrl.value)
  }
  blobUrl.value = ''
}

function isAuthUrl(url) {
  // 需要鉴权的相对路径（如 /api/file/xxx.jpg）
  return url && url.startsWith('/api/file/')
}

async function loadImage(url) {
  revoke()
  if (!url) return

  if (USE_MOCK || !isAuthUrl(url)) {
    // Mock 模式或外部 URL（http://xxx），直接用
    blobUrl.value = url
    return
  }

  try {
    // 带 token 请求图片，绕过 baseURL 直接用绝对路径
    const blob = await http.get(url, { responseType: 'blob', baseURL: '' })
    blobUrl.value = URL.createObjectURL(blob)
  } catch {
    blobUrl.value = ''
  }
}

function handleError() {
  revoke()
}

watch(() => props.src, (newSrc) => loadImage(newSrc), { immediate: true })

onBeforeUnmount(() => revoke())
</script>

<style lang="scss" scoped>
.user-avatar {
  border-radius: var(--radius-round);
  object-fit: cover;
  flex-shrink: 0;

  &--placeholder {
    display: flex;
    align-items: center;
    justify-content: center;
    background-color: var(--bg-hover);
    color: var(--text-placeholder);

    svg {
      width: 60%;
      height: 60%;
    }
  }
}
</style>
