<template>
  <div class="group-list">
    <div class="group-list__header">
      <span class="group-list__title">群组列表</span>
      <span class="group-list__count">{{ (contactStore.groups || []).length }}</span>
    </div>
    <div v-if="loading" class="group-list__loading">加载中...</div>
    <div v-else-if="!contactStore.groups || contactStore.groups.length === 0" class="group-list__empty">
      暂无群组
    </div>
    <div v-else class="group-list__items">
      <div
        v-for="group in contactStore.groups"
        :key="group.groupId || group.id"
        class="group-item"
        @click="handleSelectGroup(group)"
      >
        <UserAvatar :src="group.avatar" :size="36" />
        <div class="group-item__info">
          <span class="group-item__name">{{ group.groupName || group.name }}</span>
          <span class="group-item__members">{{ group.memberCount || 0 }} 人</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useContactStore } from '@/stores/contact.js'
import { useSessionStore } from '@/stores/session.js'
import UserAvatar from '@/components/common/UserAvatar.vue'

const contactStore = useContactStore()
const sessionStore = useSessionStore()
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    await contactStore.fetchGroups()
  } finally {
    loading.value = false
  }
})

function handleSelectGroup(group) {
  sessionStore.openSession('group', {
    id: group.groupId || group.id,
    nickname: group.groupName || group.name,
    avatar: group.avatar
  })
}
</script>

<style lang="scss" scoped>
.group-list {
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

.group-item {
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

  &__info {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  &__name {
    font-size: var(--font-size-md);
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__members {
    font-size: var(--font-size-xs);
    color: var(--text-placeholder);
  }
}
</style>
