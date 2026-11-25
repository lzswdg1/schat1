<template>
  <Teleport to="body">
    <Transition name="image-preview-fade">
      <div v-if="resolvedSrc" class="image-preview" @click="handleBackdropClick" @wheel.prevent>
        <img
          ref="imgRef"
          :src="resolvedSrc"
          alt="图片预览"
          class="image-preview__img"
          :style="imgTransform"
          :class="{ 'image-preview__img--grabbing': scale > 1 }"
          v-motion
          :initial="{ scale: 0.8, opacity: 0 }"
          :enter="{ scale: 1, opacity: 1, transition: { type: 'spring', stiffness: 200, damping: 18 } }"
          @click.stop
          @wheel="onWheel"
          @pointerdown="onPointerDown"
          @dblclick="onDoubleClick"
        />

        <!-- 缩放比例指示器 -->
        <Transition name="indicator-fade">
          <span v-if="scale !== 1" class="image-preview__indicator">{{ scalePercent }}%</span>
        </Transition>

        <!-- 操作提示 -->
        <span class="image-preview__hint">滚轮缩放 · 双击还原 · 点击背景关闭</span>

        <button class="image-preview__close" title="关闭预览" @click="$emit('close')">
          <svg viewBox="0 0 24 24" width="28" height="28" fill="currentColor">
            <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
          </svg>
        </button>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch, computed, onBeforeUnmount } from 'vue'
import { useImageZoom } from '@/composables/useImageZoom.js'
import http from '@/api/http.js'
import { USE_MOCK } from '@/api/provider.js'

const props = defineProps({
  src: {
    type: String,
    default: null
  }
})

const emit = defineEmits(['close'])

const { scale, offsetX, offsetY, scalePercent, reset, onWheel, onPointerDown, onDoubleClick } = useImageZoom()

const resolvedSrc = ref('')
const imgRef = ref(null)

const imgTransform = computed(() => ({
  transform: `translate(${offsetX.value}px, ${offsetY.value}px) scale(${scale.value})`,
  cursor: scale.value > 1 ? 'grab' : 'zoom-in'
}))

function handleBackdropClick() {
  if (scale.value !== 1) {
    reset()
  } else {
    emit('close')
  }
}

function isAuthUrl(url) {
  return url && (url.startsWith('/api/file/') || url.startsWith('/file/'))
}

function revoke() {
  if (resolvedSrc.value?.startsWith('blob:')) {
    URL.revokeObjectURL(resolvedSrc.value)
  }
  resolvedSrc.value = ''
}

async function loadImage(url) {
  revoke()
  reset()
  if (!url) return

  let finalUrl = url
  if (finalUrl.startsWith('/file/')) finalUrl = '/api' + finalUrl

  if (USE_MOCK || !isAuthUrl(url)) {
    resolvedSrc.value = url
    return
  }

  try {
    const blob = await http.get(finalUrl, { responseType: 'blob', baseURL: '' })
    resolvedSrc.value = URL.createObjectURL(blob)
  } catch {
    resolvedSrc.value = ''
  }
}

// ESC 关闭
function onKeydown(e) {
  if (e.key === 'Escape' && resolvedSrc.value) {
    if (scale.value !== 1) {
      reset()
    }
  }
}

watch(() => props.src, (url) => {
  if (url) {
    loadImage(url)
    document.addEventListener('keydown', onKeydown)
  } else {
    revoke()
    reset()
    document.removeEventListener('keydown', onKeydown)
  }
}, { immediate: true })

onBeforeUnmount(() => {
  revoke()
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style lang="scss" scoped>
.image-preview {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  background: rgba(0, 0, 0, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;

  &__img {
    max-width: 90vw;
    max-height: 90vh;
    object-fit: contain;
    border-radius: var(--radius-md);
    box-shadow: var(--shadow-lg);
    user-select: none;
    touch-action: none;
    transform-origin: center center;
    transition: transform 0.1s ease-out;
    will-change: transform;

    &--grabbing {
      cursor: grab;

      &:active {
        cursor: grabbing;
      }
    }
  }

  &__indicator {
    position: absolute;
    bottom: 60px;
    left: 50%;
    transform: translateX(-50%);
    background: rgba(0, 0, 0, 0.6);
    color: #fff;
    font-size: 13px;
    padding: 4px 12px;
    border-radius: 12px;
    pointer-events: none;
  }

  &__hint {
    position: absolute;
    bottom: 24px;
    left: 50%;
    transform: translateX(-50%);
    color: rgba(255, 255, 255, 0.45);
    font-size: 12px;
    pointer-events: none;
    white-space: nowrap;
  }

  &__close {
    position: absolute;
    top: var(--spacing-lg);
    right: var(--spacing-lg);
    display: flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border: none;
    background: rgba(255, 255, 255, 0.15);
    color: #fff;
    cursor: pointer;
    border-radius: var(--radius-round);
    transition: background 0.15s ease;

    &:hover {
      background: rgba(255, 255, 255, 0.3);
    }
  }
}

.image-preview-fade-enter-active,
.image-preview-fade-leave-active {
  transition: opacity 0.2s ease;
}

.image-preview-fade-enter-from,
.image-preview-fade-leave-to {
  opacity: 0;
}

.indicator-fade-enter-active,
.indicator-fade-leave-active {
  transition: opacity 0.15s ease;
}

.indicator-fade-enter-from,
.indicator-fade-leave-to {
  opacity: 0;
}
</style>
