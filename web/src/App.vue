<template>
  <GlobalNotification />
  <router-view v-slot="{ Component }">
    <Transition name="page-fade" mode="out-in">
      <component :is="Component" />
    </Transition>
  </router-view>
</template>

<script setup>
import GlobalNotification from '@/components/common/GlobalNotification.vue'
</script>

<style scoped>
.page-fade-enter-active {
  transition: opacity 350ms cubic-bezier(0.22, 1, 0.36, 1),
              transform 350ms cubic-bezier(0.22, 1, 0.36, 1);
}

.page-fade-leave-active {
  transition: opacity 200ms ease-out,
              transform 200ms ease-out;
}

.page-fade-enter-from {
  opacity: 0;
  transform: scale(0.97) translateY(8px);
}

.page-fade-leave-to {
  opacity: 0;
  transform: scale(0.99);
}
</style>
