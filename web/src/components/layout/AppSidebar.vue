<template>
  <aside class="app-sidebar">
    <div class="sidebar-header">
      <div class="avatar-upload" @click="triggerAvatarUpload" title="点击更换头像">
        <UserAvatar :src="authStore.user?.avatar || ''" :size="40" />
        <div class="avatar-upload__overlay">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
            <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"/>
          </svg>
        </div>
      </div>
      <input
        ref="avatarInputRef"
        type="file"
        accept="image/*"
        class="hidden-input"
        @change="handleAvatarChange"
      />
      <span class="user-nickname">{{ authStore.user?.nickname || '未登录' }}</span>
      <button class="btn-add-friend" @click="toggleSearch" title="搜索添加好友">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="12" y1="5" x2="12" y2="19" />
          <line x1="5" y1="12" x2="19" y2="12" />
        </svg>
      </button>
      <button class="btn-friend-requests" @click="showFriendRequests = true" title="好友申请">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
          <circle cx="8.5" cy="7" r="4" />
          <line x1="20" y1="8" x2="20" y2="14" />
          <line x1="23" y1="11" x2="17" y2="11" />
        </svg>
        <span v-if="contactStore.pendingRequestCount > 0" class="badge-dot">
          {{ contactStore.pendingRequestCount }}
        </span>
      </button>
    </div>

    <!-- 搜索面板：好友搜索 / 消息搜索 切换 -->
    <div v-if="showSearch" class="search-panel">
      <div class="search-tabs">
        <button
          class="search-tabs__btn"
          :class="{ 'is-active': searchTab === 'friend' }"
          @click="searchTab = 'friend'"
        >加好友</button>
        <button
          class="search-tabs__btn"
          :class="{ 'is-active': searchTab === 'message' }"
          @click="searchTab = 'message'"
        >搜消息</button>
      </div>
      <FriendSearch v-if="searchTab === 'friend'" ref="friendSearchRef" @added="handleFriendAdded" />
      <MessageSearch v-else ref="messageSearchRef" @navigate="handleMessageNavigate" />
    </div>

    <div class="sidebar-body">
      <!-- 私聊分类组 -->
      <div class="session-group">
        <div class="session-group__header" @click="sessionStore.toggleCollapse('private')">
          <span class="session-group__arrow" :class="{ 'is-collapsed': sessionStore.collapsedGroups.private }">▶</span>
          <span class="session-group__title">私聊</span>
          <span v-if="sessionStore.privateUnreadCount > 0" class="session-group__badge">
            {{ sessionStore.privateUnreadCount }}
          </span>
        </div>
        <div class="session-group__list" :class="{ 'is-collapsed': sessionStore.collapsedGroups.private }">
          <div
            v-for="session in sessionStore.privateSessions"
            :key="session.id"
            class="session-item"
            :class="{ 'is-active': session.id === sessionStore.activeSessionId }"
            @click="handleSelectSession(session.id)"
          >
            <UserAvatar :src="session.targetAvatar" :size="42" />
            <div class="session-item__content">
              <div class="session-item__top">
                <span class="session-item__name">{{ session.targetName }}</span>
                <span class="session-item__time">{{ formatTimestamp(session.lastTime) }}</span>
              </div>
              <div class="session-item__bottom">
                <span class="session-item__message">{{ session.lastMessage || '' }}</span>
                <span v-if="session.unreadCount > 0" class="session-item__unread">
                  {{ session.unreadCount }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 群聊分类组 -->
      <div class="session-group">
        <div class="session-group__header" @click="sessionStore.toggleCollapse('group')">
          <span class="session-group__arrow" :class="{ 'is-collapsed': sessionStore.collapsedGroups.group }">▶</span>
          <span class="session-group__title">群聊</span>
          <button class="btn-create-group" @click.stop="toggleCreateGroup" title="创建群聊">
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19" />
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          </button>
          <span v-if="sessionStore.groupUnreadCount > 0" class="session-group__badge">
            {{ sessionStore.groupUnreadCount }}
          </span>
        </div>
        <div class="session-group__list" :class="{ 'is-collapsed': sessionStore.collapsedGroups.group }">
          <div
            v-for="session in sessionStore.groupSessions"
            :key="session.id"
            class="session-item"
            :class="{ 'is-active': session.id === sessionStore.activeSessionId }"
            @click="handleSelectSession(session.id)"
          >
            <UserAvatar :src="session.targetAvatar" :size="42" />
            <div class="session-item__content">
              <div class="session-item__top">
                <span class="session-item__name">{{ session.targetName }}</span>
                <span class="session-item__time">{{ formatTimestamp(session.lastTime) }}</span>
              </div>
              <div class="session-item__bottom">
                <span class="session-item__message">{{ session.lastMessage || '' }}</span>
                <span v-if="session.unreadCount > 0" class="session-item__unread">
                  {{ session.unreadCount }}
                </span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 好友列表 -->
      <FriendList />

      <!-- 群组列表 -->
      <GroupList />
    </div>

    <!-- 创建群聊面板 -->
    <div v-if="showCreateGroup" class="create-group-panel">
      <CreateGroup @close="showCreateGroup = false" @created="handleGroupCreated" />
    </div>

    <div class="sidebar-footer">
      <button class="btn-logout" @click="handleLogout">退出登录</button>
    </div>

    <!-- 好友申请弹窗 -->
    <FriendRequestModal
      :visible="showFriendRequests"
      @close="showFriendRequests = false"
    />
  </aside>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'
import { useSessionStore } from '@/stores/session.js'
import { useContactStore } from '@/stores/contact.js'
import UserAvatar from '@/components/common/UserAvatar.vue'
import FriendList from '@/components/contact/FriendList.vue'
import FriendSearch from '@/components/contact/FriendSearch.vue'
import GroupList from '@/components/contact/GroupList.vue'
import CreateGroup from '@/components/contact/CreateGroup.vue'
import FriendRequestModal from '@/components/contact/FriendRequestModal.vue'
import MessageSearch from '@/components/chat/MessageSearch.vue'

import { uploadFile } from '@/api/chat.js'
import { updateAvatar as apiUpdateAvatar } from '@/api/auth.js'
import { normalizeFileUrl } from '@/api/http.js'

const router = useRouter()
const authStore = useAuthStore()
const sessionStore = useSessionStore()
const contactStore = useContactStore()

const showSearch = ref(false)
const friendSearchRef = ref(null)
const messageSearchRef = ref(null)
const searchTab = ref('friend')
const showCreateGroup = ref(false)
const avatarInputRef = ref(null)
const showFriendRequests = ref(false)

onMounted(async () => {
  // 先加载好友列表，确保 normalizeMessage 能查到 senderName
  await contactStore.fetchFriends()
  await sessionStore.loadSessions()
  contactStore.fetchFriendRequests()
})

function toggleSearch() {
  showSearch.value = !showSearch.value
  if (!showSearch.value) {
    if (friendSearchRef.value) friendSearchRef.value.reset()
    if (messageSearchRef.value) messageSearchRef.value.clearSearch()
    searchTab.value = 'friend'
  }
}

function handleFriendAdded() {
  showSearch.value = false
}

function handleMessageNavigate() {
  showSearch.value = false
}

function toggleCreateGroup() {
  showCreateGroup.value = !showCreateGroup.value
}

function handleGroupCreated() {
  showCreateGroup.value = false
}

function handleSelectSession(sessionId) {
  sessionStore.setActiveSession(sessionId)
  sessionStore.clearUnread(sessionId)
}

function formatTimestamp(timestamp) {
  if (!timestamp) return ''

  const date = new Date(timestamp)
  const now = new Date()

  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yesterday = new Date(today.getTime() - 86400000)
  const target = new Date(date.getFullYear(), date.getMonth(), date.getDate())

  if (target.getTime() === today.getTime()) {
    const hours = String(date.getHours()).padStart(2, '0')
    const minutes = String(date.getMinutes()).padStart(2, '0')
    return `${hours}:${minutes}`
  }

  if (target.getTime() === yesterday.getTime()) {
    return '昨天'
  }

  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${month}/${day}`
}

async function handleLogout() {
  await authStore.logout()
  router.push('/login')
}

function triggerAvatarUpload() {
  avatarInputRef.value?.click()
}

async function handleAvatarChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  e.target.value = ''

  try {
    // 1. 上传文件
    const uploadRes = await uploadFile(file)
    if (!uploadRes) return
    // 兼容两种返回格式：字符串路径 或 { url, name, ... } 对象
    const rawUrl = uploadRes.code === 200
      ? (typeof uploadRes.data === 'string' ? uploadRes.data : uploadRes.data?.url)
      : null
    if (!rawUrl) return
    const avatarUrl = normalizeFileUrl(rawUrl)

    // 2. 调后端更新头像
    const updateRes = await apiUpdateAvatar(avatarUrl)
    if (updateRes.code !== 200) return

    // 3. 更新本地状态
    authStore.updateAvatar(avatarUrl)
  } catch (err) {
    console.error('[AvatarUpload] 上传失败:', err)
  }
}
</script>

<style lang="scss" scoped>
.app-sidebar {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: var(--bg-sidebar);
  border-right: 1px solid var(--border-light);
}

.sidebar-header {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-lg);
  border-bottom: 1px solid var(--border-light);
}

.avatar-upload {
  position: relative;
  cursor: pointer;
  border-radius: var(--radius-round);
  flex-shrink: 0;

  &__overlay {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    background: rgba(0, 0, 0, 0.4);
    border-radius: var(--radius-round);
    color: white;
    opacity: 0;
    transition: opacity 200ms cubic-bezier(0.22, 1, 0.36, 1);
  }

  &:hover .avatar-upload__overlay {
    opacity: 1;
  }
}

.hidden-input {
  display: none;
}

.user-nickname {
  flex: 1;
  font-size: var(--font-size-lg);
  font-weight: 500;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.btn-add-friend {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color var(--transition-fast), color var(--transition-fast);

  &:hover {
    background-color: var(--bg-hover);
    color: var(--text-primary);
  }
}

.btn-friend-requests {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  padding: 0;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color var(--transition-fast), color var(--transition-fast);

  &:hover {
    background-color: var(--bg-hover);
    color: var(--text-primary);
  }
}

.badge-dot {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
  color: var(--text-inverse);
  background-color: var(--text-danger);
  border-radius: 8px;
}

.search-panel {
  border-bottom: 1px solid var(--border-light);
  background-color: var(--bg-sidebar);
}

.search-tabs {
  display: flex;
  padding: var(--spacing-sm) var(--spacing-md) 0;
  gap: var(--spacing-xs);

  &__btn {
    flex: 1;
    padding: var(--spacing-xs) 0;
    font-size: var(--font-size-sm);
    font-weight: 500;
    color: var(--text-secondary);
    background: transparent;
    border: none;
    border-bottom: 2px solid transparent;
    cursor: pointer;
    transition: color 200ms ease, border-color 200ms ease;

    &:hover {
      color: var(--text-primary);
    }

    &.is-active {
      color: var(--color-primary);
      border-bottom-color: var(--color-primary);
    }
  }
}

.create-group-panel {
  border-bottom: 1px solid var(--border-light);
  background-color: var(--bg-sidebar);
}

.btn-create-group {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  color: var(--text-secondary);
  background: transparent;
  border: none;
  border-radius: var(--radius-sm);
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color var(--transition-fast), color var(--transition-fast);

  &:hover {
    background-color: var(--bg-hover);
    color: var(--text-primary);
  }
}

.sidebar-body {
  flex: 1;
  overflow-y: auto;
}

.session-group {
  &__header {
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
    padding: var(--spacing-sm) var(--spacing-lg);
    cursor: pointer;
    user-select: none;
    color: var(--text-secondary);
    font-size: var(--font-size-sm);
    transition: background-color var(--transition-fast);

    &:hover {
      background-color: var(--bg-hover);
    }
  }

  &__arrow {
    display: inline-block;
    font-size: 10px;
    transition: transform 350ms cubic-bezier(0.22, 1, 0.36, 1);
    transform: rotate(90deg);

    &.is-collapsed {
      transform: rotate(0deg);
    }
  }

  &__title {
    flex: 1;
    font-weight: 500;
  }

  &__badge {
    min-width: 18px;
    height: 18px;
    padding: 0 5px;
    font-size: var(--font-size-xs);
    line-height: 18px;
    text-align: center;
    color: var(--text-inverse);
    background-color: var(--text-danger);
    border-radius: 9px;
  }

  &__list {
    overflow: hidden;
    max-height: 2000px;
    transition: max-height 350ms cubic-bezier(0.22, 1, 0.36, 1),
                opacity 250ms ease;
    opacity: 1;

    &.is-collapsed {
      max-height: 0;
      opacity: 0;
    }
  }
}

.session-item {
  display: flex;
  align-items: center;
  gap: var(--spacing-md);
  padding: var(--spacing-md) var(--spacing-lg);
  cursor: pointer;
  transition: background-color 200ms cubic-bezier(0.22, 1, 0.36, 1),
              transform 200ms cubic-bezier(0.22, 1, 0.36, 1);

  &:hover {
    background-color: var(--bg-hover);
  }

  &:active {
    transform: scale(0.98);
    transition-duration: 80ms;
  }

  &.is-active {
    background-color: var(--bg-active);
  }

  &__content {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: var(--spacing-xs);
  }

  &__top {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__name {
    font-size: var(--font-size-md);
    font-weight: 500;
    color: var(--text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__time {
    font-size: var(--font-size-xs);
    color: var(--text-secondary);
    flex-shrink: 0;
    margin-left: var(--spacing-sm);
  }

  &__bottom {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  &__message {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    flex: 1;
    min-width: 0;
  }

  &__unread {
    min-width: 18px;
    height: 18px;
    padding: 0 5px;
    font-size: var(--font-size-xs);
    line-height: 18px;
    text-align: center;
    color: var(--text-inverse);
    background-color: var(--text-danger);
    border-radius: 9px;
    flex-shrink: 0;
    margin-left: var(--spacing-sm);
  }
}

.sidebar-footer {
  padding: var(--spacing-md) var(--spacing-lg);
  border-top: 1px solid var(--border-light);
}

.btn-logout {
  width: 100%;
  padding: var(--spacing-sm) var(--spacing-md);
  font-size: var(--font-size-md);
  color: var(--text-danger);
  background-color: transparent;
  border: 1px solid var(--border-color);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color 200ms ease,
              transform 200ms cubic-bezier(0.22, 1, 0.36, 1);

  &:hover {
    background-color: var(--bg-hover);
  }

  &:active {
    transform: scale(0.97);
    transition-duration: 80ms;
  }
}
</style>
