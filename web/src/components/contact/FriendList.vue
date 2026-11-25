<template>
  <div class="friend-list">
    <div class="friend-list__header">
      <span class="friend-list__title">好友列表</span>
      <span class="friend-list__count">{{ contactStore.friends.length }}</span>
    </div>
    <div v-if="loading" class="friend-list__loading">加载中...</div>
    <div v-else-if="contactStore.friends.length === 0" class="friend-list__empty">
      暂无好友
    </div>
    <div v-else class="friend-list__items">
      <div
        v-for="friend in contactStore.friends"
        :key="friend.id"
        class="friend-item"
        @click="handleSelectFriend(friend)"
        @contextmenu.prevent="handleContextMenu($event, friend)"
      >
        <UserAvatar :src="friend.avatar" :size="36" />
        <span class="friend-item__name">{{ friend.nickname }}</span>
      </div>
    </div>

    <ContextMenu
      :visible="contextMenuVisible"
      :position="contextMenuPosition"
      :items="contextMenuItems"
      @select="handleMenuSelect"
      @close="contextMenuVisible = false"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useContactStore } from '@/stores/contact.js'
import { useSessionStore } from '@/stores/session.js'
import { useFriendList } from '@/composables/useContact.js'
import UserAvatar from '@/components/common/UserAvatar.vue'
import ContextMenu from '@/components/common/ContextMenu.vue'

const contactStore = useContactStore()
const sessionStore = useSessionStore()
const { loading, deleteFriend, loadFriends } = useFriendList()

const contextMenuVisible = ref(false)
const contextMenuPosition = ref({ x: 0, y: 0 })
const selectedFriend = ref(null)

const contextMenuItems = [
  { label: '删除好友', action: 'delete', danger: true }
]

onMounted(() => {
  loadFriends()
})

function handleContextMenu(event, friend) {
  selectedFriend.value = friend
  contextMenuPosition.value = { x: event.clientX, y: event.clientY }
  contextMenuVisible.value = true
}

function handleSelectFriend(friend) {
  sessionStore.openSession('private', friend)
}

function handleMenuSelect(item) {
  if (item.action === 'delete' && selectedFriend.value) {
    deleteFriend(selectedFriend.value.id)
  }
}
</script>

<style lang="scss" scoped>
.friend-list {
  &__header {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-sm) var(--spacing-lg);
    color: var(--text-secondary);
    font-size: var(--font-size-sm);
    font-weight: 500;
  }

  &__count {
    font-size: var(--font-size-xs);
    color: var(--text-placeholder);
  }

  &__loading,
  &__empty {
    padding: var(--spacing-lg);
    text-align: center;
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
  }
}

.friend-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-sm) var(--spacing-lg);
  cursor: pointer;
  transition: background-color 200ms cubic-bezier(0.22, 1, 0.36, 1),
              transform 200ms cubic-bezier(0.22, 1, 0.36, 1);
  user-select: none;

  &:hover {
    background-color: var(--bg-hover);
  }

  &:active {
    transform: scale(0.98);
    transition-duration: 80ms;
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
