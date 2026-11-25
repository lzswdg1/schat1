<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="visible" class="modal-overlay" @click="$emit('close')">
        <div class="modal-content" @click.stop>
          <div class="modal-header">
            <span class="modal-title">好友申请</span>
            <button class="modal-close" @click="$emit('close')">✕</button>
          </div>
          <div class="modal-body">
            <div v-if="loading" class="modal-loading">加载中...</div>
            <div v-else-if="contactStore.friendRequests.length === 0" class="modal-empty">
              暂无好友申请
            </div>
            <div v-else class="request-list">
              <div
                v-for="req in contactStore.friendRequests"
                :key="req.id"
                class="request-item"
              >
                <UserAvatar :src="req.fromUserAvatar" :size="40" />
                <div class="request-item__info">
                  <span class="request-item__name">{{ req.fromUserName }}</span>
                  <span class="request-item__time">{{ formatTime(req.timestamp) }}</span>
                </div>
                <div class="request-item__actions">
                  <button
                    class="request-item__btn request-item__btn--accept"
                    :disabled="processing"
                    @click="handleAccept(req.id)"
                  >同意</button>
                  <button
                    class="request-item__btn request-item__btn--reject"
                    :disabled="processing"
                    @click="handleReject(req.id)"
                  >拒绝</button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useContactStore } from '@/stores/contact.js'
import UserAvatar from '@/components/common/UserAvatar.vue'

const props = defineProps({
  visible: { type: Boolean, default: false }
})

const emit = defineEmits(['close'])

const contactStore = useContactStore()
const loading = ref(false)
const processing = ref(false)

watch(() => props.visible, async (val) => {
  if (val) {
    loading.value = true
    await contactStore.fetchFriendRequests()
    loading.value = false
  }
})

async function handleAccept(requestId) {
  processing.value = true
  await contactStore.acceptRequest(requestId)
  processing.value = false
}

async function handleReject(requestId) {
  processing.value = true
  await contactStore.rejectRequest(requestId)
  processing.value = false
}

function formatTime(ts) {
  const d = new Date(ts)
  return `${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
</script>

<style lang="scss" scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-modal);
  background: var(--bg-mask);
  display: flex;
  align-items: center;
  justify-content: center;
}

.modal-content {
  width: 380px;
  max-height: 500px;
  background: var(--bg-white);
  border-radius: 12px;
  box-shadow: var(--shadow-lg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--spacing-lg);
  border-bottom: 1px solid var(--border-light);
}

.modal-title {
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--text-primary);
}

.modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  font-size: 16px;
  color: var(--text-secondary);
  cursor: pointer;
  border-radius: var(--radius-sm);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 150ms ease;

  &:hover {
    background: var(--bg-hover);
  }
}

.modal-body {
  flex: 1;
  overflow-y: auto;
  padding: var(--spacing-md);
}

.modal-loading, .modal-empty {
  text-align: center;
  padding: var(--spacing-xl);
  color: var(--text-secondary);
  font-size: var(--font-size-md);
}

.request-list {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-sm);
}

.request-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  border-radius: var(--radius-md);
  transition: background 150ms ease;

  &:hover { background: var(--bg-hover); }

  &__info {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  &__name {
    font-size: var(--font-size-md);
    font-weight: 500;
    color: var(--text-primary);
  }

  &__time {
    font-size: var(--font-size-xs);
    color: var(--text-secondary);
  }

  &__actions {
    display: flex;
    gap: var(--spacing-xs);
    flex-shrink: 0;
  }

  &__btn {
    padding: 4px 12px;
    font-size: var(--font-size-sm);
    font-weight: 500;
    border: none;
    border-radius: 6px;
    cursor: pointer;
    transition: opacity 150ms ease;

    &:disabled { opacity: 0.4; cursor: not-allowed; }

    &--accept {
      background: var(--color-primary);
      color: var(--text-inverse);
    }

    &--reject {
      background: var(--bg-hover);
      color: var(--text-secondary);
    }
  }
}

.modal-fade-enter-active,
.modal-fade-leave-active {
  transition: opacity 200ms ease;
}
.modal-fade-enter-from,
.modal-fade-leave-to {
  opacity: 0;
}
</style>
