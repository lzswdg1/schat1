<template>
  <div class="image-message" @click="handlePreview">
    <img
      v-if="imageSrc"
      class="image-message__thumbnail"
      :src="imageSrc"
      alt="图片消息"
      loading="lazy"
      @error="handleError"
    />
    <div v-else class="image-message__placeholder">图片加载中...</div>
  </div>
</template>

<script setup>
import { ref, watch, onBeforeUnmount } from 'vue'
import http from '@/api/http.js'
import { USE_MOCK } from '@/api/provider.js'

const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['preview'])

const imageSrc = ref('')

function isAuthUrl(url) {
  return url && (url.startsWith('/api/file/') || url.startsWith('/file/'))
}

function revoke() {
  if (imageSrc.value?.startsWith('blob:')) {
    URL.revokeObjectURL(imageSrc.value)
  }
  imageSrc.value = ''
}

async function loadImage(url) {
  revoke()
  if (!url) return

  // 补全 /file/ → /api/file/，确保走 Vite 代理 + 网关鉴权
  let finalUrl = url
  if (finalUrl.startsWith('/file/')) finalUrl = '/api' + finalUrl

  if (USE_MOCK || !isAuthUrl(url)) {
    imageSrc.value = url
    return
  }

  try {
    const blob = await http.get(finalUrl, { responseType: 'blob', baseURL: '' })
    imageSrc.value = URL.createObjectURL(blob)
  } catch {
    imageSrc.value = ''
  }
}

function handleError() {
  revoke()
}

function handlePreview() {
  emit('preview', props.message.content)
}

watch(() => props.message.content, (url) => loadImage(url), { immediate: true })

onBeforeUnmount(() => revoke())
</script>

<style lang="scss" scoped>
.image-message {
  cursor: pointer;

  &__thumbnail {
    display: block;
    max-width: 240px;
    border-radius: var(--radius-md);
    object-fit: cover;
    transition: opacity var(--transition-fast) ease;

    &:hover {
      opacity: 0.85;
    }
  }

  &__placeholder {
    width: 120px;
    height: 80px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--bg-hover);
    border-radius: var(--radius-md);
    font-size: 12px;
    color: var(--text-secondary);
  }
}
</style>
