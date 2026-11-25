<template>
  <div class="create-group">
    <div class="create-group__header">
      <span class="create-group__title">创建群聊</span>
      <button class="create-group__close" @click="emit('close')" title="关闭">✕</button>
    </div>

    <form class="create-group__form" @submit.prevent="handleSubmit">
      <div class="create-group__field">
        <label class="create-group__label">群名称</label>
        <input
          v-model="groupName"
          class="create-group__input"
          type="text"
          placeholder="请输入群名称"
        />
      </div>

      <div class="create-group__field">
        <label class="create-group__label">选择好友</label>
        <div v-if="contactStore.friends.length === 0" class="create-group__empty">
          暂无好友可选
        </div>
        <div v-else class="create-group__friend-list">
          <label
            v-for="friend in contactStore.friends"
            :key="friend.id"
            class="friend-check-item"
          >
            <input
              type="checkbox"
              :value="friend.id"
              v-model="selectedIds"
              class="friend-check-item__checkbox"
            />
            <UserAvatar :src="friend.avatar" :size="28" />
            <span class="friend-check-item__name">{{ friend.nickname }}</span>
          </label>
        </div>
      </div>

      <div v-if="errorMsg" class="create-group__error">{{ errorMsg }}</div>

      <button
        class="create-group__submit"
        type="submit"
        :disabled="!canSubmit || submitting"
      >
        {{ submitting ? '创建中...' : '创建群聊' }}
      </button>
    </form>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useContactStore } from '@/stores/contact.js'
import { useSessionStore } from '@/stores/session.js'
import UserAvatar from '@/components/common/UserAvatar.vue'

const emit = defineEmits(['close', 'created'])

const contactStore = useContactStore()
const sessionStore = useSessionStore()

const groupName = ref('')
const selectedIds = ref([])
const submitting = ref(false)
const errorMsg = ref('')

const canSubmit = computed(() => {
  return groupName.value.trim() && selectedIds.value.length > 0
})

async function handleSubmit() {
  errorMsg.value = ''

  if (!groupName.value.trim()) {
    errorMsg.value = '请输入群名称'
    return
  }
  if (selectedIds.value.length === 0) {
    errorMsg.value = '请至少选择一个好友'
    return
  }

  submitting.value = true
  try {
    const res = await contactStore.createGroup({
      name: groupName.value.trim(),
      memberIds: [...selectedIds.value]
    })
    if (res.code === 200) {
      sessionStore.loadSessions()
      groupName.value = ''
      selectedIds.value = []
      emit('created')
    } else {
      errorMsg.value = res.message || '创建失败'
    }
  } catch {
    errorMsg.value = '创建失败，请重试'
  } finally {
    submitting.value = false
  }
}
</script>

<style lang="scss" scoped>
.create-group {
  padding: var(--spacing-md);

  &__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: var(--spacing-md);
  }

  &__title {
    font-size: var(--font-size-md);
    font-weight: 500;
    color: var(--text-primary);
  }

  &__close {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 24px;
    height: 24px;
    padding: 0;
    font-size: 14px;
    color: var(--text-secondary);
    background: transparent;
    border: none;
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: background-color var(--transition-fast), color var(--transition-fast);

    &:hover {
      background-color: var(--bg-hover);
      color: var(--text-primary);
    }
  }

  &__form {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
  }

  &__field {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xs);
  }

  &__label {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    font-weight: 500;
  }

  &__input {
    padding: var(--spacing-sm) var(--spacing-md);
    font-size: var(--font-size-md);
    border: 1.5px solid transparent;
    border-radius: 10px;
    outline: none;
    background-color: #f8f8f8;
    color: var(--text-primary);
    transition: border-color 300ms cubic-bezier(0.22, 1, 0.36, 1),
                background-color 300ms ease,
                box-shadow 300ms ease;

    &:focus {
      border-color: var(--color-primary);
      background-color: var(--bg-white);
      box-shadow: 0 0 0 3px rgba(7, 193, 96, 0.1);
    }

    &::placeholder {
      color: var(--text-placeholder);
    }
  }

  &__friend-list {
    max-height: 200px;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xs);
  }

  &__empty {
    padding: var(--spacing-sm) 0;
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    text-align: center;
  }

  &__error {
    font-size: var(--font-size-sm);
    color: var(--text-danger);
  }

  &__submit {
    padding: var(--spacing-sm) var(--spacing-md);
    font-size: var(--font-size-md);
    font-weight: 500;
    color: var(--text-inverse);
    background: linear-gradient(135deg, #07c160 0%, #06ad56 100%);
    border: none;
    border-radius: 10px;
    cursor: pointer;
    transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1),
                box-shadow 200ms ease,
                opacity 200ms ease;

    &:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 4px 12px rgba(7, 193, 96, 0.25);
    }

    &:active:not(:disabled) {
      transform: scale(0.97);
      transition-duration: 80ms;
    }

    &:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }
}

.friend-check-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-sm);
  padding: var(--spacing-xs) var(--spacing-sm);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: background-color var(--transition-fast);

  &:hover {
    background-color: var(--bg-hover);
  }

  &__checkbox {
    accent-color: var(--color-primary);
    width: 16px;
    height: 16px;
    cursor: pointer;
  }

  &__name {
    font-size: var(--font-size-md);
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}
</style>
