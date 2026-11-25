<template>
  <div class="home-layout">
    <AppSidebar class="home-sidebar" />
    <ChatArea class="home-main" />
  </div>
</template>

<script setup>
import AppSidebar from '@/components/layout/AppSidebar.vue'
import ChatArea from '@/components/layout/ChatArea.vue'
import { useMessageListener } from '@/composables/useMessageListener.js'

// 启动消息推送监听
useMessageListener()
</script>

<style lang="scss" scoped>
.home-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.home-sidebar {
  width: var(--sidebar-width);
  flex-shrink: 0;
}

.home-main {
  flex: 1;
  min-width: 0;
}
</style>
