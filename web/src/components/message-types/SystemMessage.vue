<template>
  <div class="system-message">
    <span class="system-message__text">{{ message.content }}</span>
  </div>
</template>

<script setup>
defineProps({
  message: {
    type: Object,
    required: true
  }
})
</script>

<style lang="scss" scoped>
.system-message {
  display: flex;
  justify-content: center;
  padding: var(--spacing-sm) 0;

  &__text {
    font-size: var(--font-size-sm);
    color: var(--text-system);
    line-height: var(--line-height-normal);
    text-align: center;
  }
}
</style>
