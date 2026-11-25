<template>
  <div class="quote-preview">
    <div class="quote-preview__bar"></div>
    <div class="quote-preview__body">
      <span class="quote-preview__sender">{{ message.senderName }}</span>
      <span class="quote-preview__content">{{ message.content }}</span>
    </div>
    <button class="quote-preview__close" title="取消引用" @click="$emit('close')">
      <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor">
        <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z" />
      </svg>
    </button>
  </div>
</template>

<script setup>
defineProps({
  message: {
    type: Object,
    required: true
  }
})

defineEmits(['close'])
</script>

<style lang="scss" scoped>
.quote-preview {
  display: flex;
  align-items: center;
  padding: var(--spacing-sm) var(--spacing-md);
  background: var(--bg-hover);
  border-bottom: 1px solid var(--border-light);
  gap: var(--spacing-sm);

  &__bar {
    width: 3px;
    height: 24px;
    background: var(--color-primary);
    border-radius: 2px;
    flex-shrink: 0;
  }

  &__body {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  &__sender {
    font-size: var(--font-size-xs);
    color: var(--text-link);
    font-weight: 500;
  }

  &__content {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__close {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 20px;
    height: 20px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    cursor: pointer;
    border-radius: var(--radius-sm);
    flex-shrink: 0;
    transition: background var(--transition-fast) ease, color var(--transition-fast) ease;

    &:hover {
      background: var(--bg-active);
      color: var(--text-primary);
    }
  }
}
</style>
