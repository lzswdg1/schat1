<template>
  <div class="file-message" @click="handleDownload">
    <div class="file-message__icon">📄</div>
    <div class="file-message__info">
      <span class="file-message__name">{{ fileMeta.name }}</span>
      <span class="file-message__size">{{ formattedSize }}</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  message: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['download'])

const fileMeta = computed(() => props.message.fileMeta || { name: '未知文件', size: 0, mimeType: '' })

const formattedSize = computed(() => {
  const bytes = fileMeta.value.size || 0
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
})

function handleDownload() {
  emit('download', props.message)
}
</script>

<style lang="scss" scoped>
.file-message {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-hover);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background var(--transition-fast) ease;

  &:hover {
    background: var(--bg-active);
  }

  &__icon {
    font-size: 28px;
    flex-shrink: 0;
  }

  &__info {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  &__name {
    font-size: var(--font-size-md);
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__size {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    margin-top: 2px;
  }
}
</style>
