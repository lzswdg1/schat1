<template>
  <div class="text-message">
    <p class="text-message__content">{{ message.content }}</p>
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
.text-message {
  &__content {
    margin: 0;
    font-size: var(--font-size-md);
    line-height: var(--line-height-normal);
    color: var(--text-primary);
    word-wrap: break-word;
    overflow-wrap: break-word;
    white-space: pre-wrap;
  }
}
</style>
