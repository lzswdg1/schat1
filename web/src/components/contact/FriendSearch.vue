<template>
  <div class="friend-search">
    <form class="friend-search__form" @submit.prevent="handleSearch">
      <input
        v-model="searchId"
        class="friend-search__input"
        type="text"
        placeholder="输入账号搜索添加好友"
      />
      <button
        class="friend-search__btn"
        type="submit"
        :disabled="!searchId.trim() || searchLoading"
      >
        搜索
      </button>
    </form>

    <div v-if="searchLoading" class="friend-search__status">搜索中...</div>

    <div v-else-if="searched && contactStore.searchResult" class="friend-search__result">
      <div class="search-result-card">
        <UserAvatar :src="contactStore.searchResult.avatar" :size="42" />
        <div class="search-result-card__info">
          <span class="search-result-card__name">{{ contactStore.searchResult.nickname }}</span>
          <span class="search-result-card__id">ID: {{ contactStore.searchResult.username || contactStore.searchResult.id }}</span>
        </div>
        <button
          v-if="isFriend"
          class="search-result-card__chat-btn"
          @click="handleChat"
        >
          发消息
        </button>
        <button
          v-else
          class="search-result-card__add-btn"
          :disabled="addLoading"
          @click="handleAdd"
        >
          {{ addLoading ? '申请中...' : '发起申请' }}
        </button>
      </div>
    </div>

    <div v-else-if="searched && !contactStore.searchResult" class="friend-search__status">
      未找到该用户
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useContactStore } from '@/stores/contact.js'
import { useSessionStore } from '@/stores/session.js'
import { useFriendSearch } from '@/composables/useContact.js'
import UserAvatar from '@/components/common/UserAvatar.vue'

const emit = defineEmits(['added'])

const contactStore = useContactStore()
const sessionStore = useSessionStore()
const { searchLoading, addLoading, searched, search, addFriend, reset } = useFriendSearch()

const searchId = ref('')

const isFriend = computed(() => {
  if (!contactStore.searchResult) return false
  return contactStore.friends.some(f => String(f.id) === String(contactStore.searchResult.id))
})

async function handleSearch() {
  if (!searchId.value.trim()) return
  await search(searchId.value)
}

async function handleAdd() {
  if (!contactStore.searchResult) return
  // 后端 /api/friend/apply 需要 targetAccountId（账号名），不是数字 ID
  const res = await addFriend(contactStore.searchResult.username)
  if (res.code === 200) {
    searchId.value = ''
    emit('added')
  } else {
    alert(res.message || '申请失败，请稍后重试')
  }
}

function handleChat() {
  if (!contactStore.searchResult) return
  const user = contactStore.searchResult
  sessionStore.openSession('private', { id: user.id, nickname: user.nickname, avatar: user.avatar })
  searchId.value = ''
  emit('added')
}

defineExpose({ reset })
</script>

<style lang="scss" scoped>
.friend-search {
  padding: var(--spacing-md);

  &__form {
    display: flex;
    gap: var(--spacing-sm);
  }

  &__input {
    flex: 1;
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

  &__btn {
    padding: var(--spacing-sm) var(--spacing-md);
    font-size: var(--font-size-md);
    font-weight: 500;
    color: var(--text-inverse);
    background: linear-gradient(135deg, #07c160 0%, #06ad56 100%);
    border: none;
    border-radius: 10px;
    cursor: pointer;
    white-space: nowrap;
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

  &__status {
    padding: var(--spacing-md) 0;
    text-align: center;
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
  }

  &__result {
    margin-top: var(--spacing-md);
  }
}

.search-result-card {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md);
  background-color: var(--bg-white);
  border-radius: var(--radius-md);
  border: 1px solid var(--border-light);

  &__info {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xs);
    min-width: 0;
  }

  &__name {
    font-size: var(--font-size-md);
    font-weight: 500;
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__id {
    font-size: var(--font-size-xs);
    color: var(--text-secondary);
  }

  &__add-btn {
    padding: var(--spacing-xs) var(--spacing-md);
    font-size: var(--font-size-sm);
    font-weight: 500;
    color: var(--text-inverse);
    background: linear-gradient(135deg, #07c160 0%, #06ad56 100%);
    border: none;
    border-radius: 8px;
    cursor: pointer;
    white-space: nowrap;
    flex-shrink: 0;
    transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1),
                box-shadow 200ms ease,
                opacity 200ms ease;

    &:hover:not(:disabled) {
      transform: translateY(-1px);
      box-shadow: 0 3px 10px rgba(7, 193, 96, 0.25);
    }

    &:active:not(:disabled) {
      transform: scale(0.96);
      transition-duration: 80ms;
    }

    &:disabled {
      opacity: 0.4;
      cursor: not-allowed;
    }
  }

  &__chat-btn {
    padding: var(--spacing-xs) var(--spacing-md);
    font-size: var(--font-size-sm);
    font-weight: 500;
    color: var(--color-primary);
    background: transparent;
    border: 1.5px solid var(--color-primary);
    border-radius: 8px;
    cursor: pointer;
    white-space: nowrap;
    flex-shrink: 0;
    transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1),
                background-color 200ms ease;

    &:hover {
      background-color: rgba(7, 193, 96, 0.08);
      transform: translateY(-1px);
    }

    &:active {
      transform: scale(0.96);
      transition-duration: 80ms;
    }
  }
}
</style>
