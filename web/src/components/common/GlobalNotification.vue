<template>
  <Transition name="notification-slide">
    <div
      v-if="notificationStore.current"
      class="global-notification"
      :class="`global-notification--${notificationStore.current.type}`"
      role="alert"
    >
      <span class="global-notification__text">{{ notificationStore.current.message }}</span>
      <button
        class="global-notification__close"
        aria-label="关闭通知"
        @click="notificationStore.dismiss()"
      >×</button>
    </div>
  </Transition>
</template>

<script setup>
import { useNotificationStore } from '@/stores/notification.js'

const notificationStore = useNotificationStore()
</script>

<style lang="scss" scoped>
.global-notification {
  position: fixed;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 9999;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-lg);
  font-size: var(--font-size-md);
  max-width: 480px;

  &--info {
    background: var(--bg-white);
    color: var(--text-primary);
    border: 1px solid var(--border-light);
  }

  &--warning {
    background: #fff8e6;
    color: #946300;
    border: 1px solid #ffe58f;
  }

  &--error {
    background: #fff1f0;
    color: var(--text-danger);
    border: 1px solid #ffccc7;
  }

  &__text {
    flex: 1;
    line-height: 1.4;
  }

  &__close {
    flex-shrink: 0;
    border: none;
    background: transparent;
    font-size: 18px;
    color: inherit;
    opacity: 0.5;
    cursor: pointer;
    padding: 0 2px;
    line-height: 1;

    &:hover {
      opacity: 1;
    }
  }
}

.notification-slide-enter-active {
  transition: opacity 300ms var(--spring), transform 300ms var(--spring);
}

.notification-slide-leave-active {
  transition: opacity 200ms ease-out, transform 200ms ease-out;
}

.notification-slide-enter-from {
  opacity: 0;
  transform: translateX(-50%) translateY(-20px);
}

.notification-slide-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-10px);
}
</style>
