<template>
  <main class="chat-area">
    <template v-if="sessionStore.activeSession">
      <!-- 聊天头部 -->
      <div class="chat-area__header">
        <span class="chat-area__title">{{ sessionStore.activeSession.targetName }}</span>
        <button
          v-if="sessionStore.activeSession.type === 'group'"
          class="chat-area__group-info-btn"
          title="群详情"
          @click="showGroupDetail = true"
        >
          <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor">
            <path d="M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-4.67-3.5-7-3.5z"/>
          </svg>
        </button>
      </div>

      <!-- 消息列表 -->
      <MessageList
        ref="messageListRef"
        :session-id="sessionStore.activeSessionId"
        :loading-more="loadingMore"
        @quote="handleQuote"
        @recall="handleRecall"
        @preview="handlePreview"
        @load-more="handleLoadMore"
        @retry="handleRetry"
      />

      <!-- 消息输入框 -->
      <MessageInput
        :session-id="sessionStore.activeSessionId"
        @send="handleSend"
      />

      <!-- 图片全屏预览 -->
      <ImagePreview
        :src="previewImage"
        @close="closeImagePreview"
      />

      <!-- 群详情面板 -->
      <GroupDetailPanel
        :visible="showGroupDetail"
        :group-id="sessionStore.activeSession?.type === 'group' ? sessionStore.activeSession.targetId : ''"
        :group-name="sessionStore.activeSession?.targetName || ''"
        @close="showGroupDetail = false"
      />
    </template>
    <template v-else>
      <div class="empty-state" v-motion-empty-fade>
        <div class="empty-icon">💬</div>
        <p class="empty-text">选择一个会话开始聊天</p>
      </div>
    </template>
  </main>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useSessionStore } from '@/stores/session.js'
import { useChatStore } from '@/stores/chat.js'
import { useChat } from '@/composables/useChat.js'
import MessageList from '@/components/chat/MessageList.vue'
import MessageInput from '@/components/chat/MessageInput.vue'
import ImagePreview from '@/components/chat/ImagePreview.vue'
import GroupDetailPanel from '@/components/contact/GroupDetailPanel.vue'

const sessionStore = useSessionStore()
const chatStore = useChatStore()

const {
  loadingMore,
  previewImage,
  uploadProgress,
  isUploading,
  loadHistory,
  loadMore,
  sendMessage,
  recallMessage,
  setQuote,
  closeImagePreview,
  markAsRead
} = useChat()

const messageListRef = ref(null)
const showGroupDetail = ref(false)

// 进入会话时加载历史消息、清除未读、发送已读回执
watch(
  () => sessionStore.activeSessionId,
  async (sessionId, oldSessionId) => {
    // 优化原因：释放非活跃会话的内存缓存，只保留当前会话在 Pinia 中
    if (oldSessionId && oldSessionId !== sessionId) {
      chatStore.evictSession(oldSessionId)
    }
    showGroupDetail.value = false
    if (sessionId) {
      // 群聊：预加载群成员信息（用于显示昵称头像）
      if (sessionId.startsWith('group_')) {
        const groupId = sessionId.replace('group_', '')
        const { useContactStore } = await import('@/stores/contact.js')
        await useContactStore().fetchGroupMembers(groupId)
      }
      try {
        await loadHistory(sessionId)
      } catch {
        // 历史加载失败不阻塞
      }
      sessionStore.clearUnread(sessionId)
      // 消息加载完后再发已读回执，确保 unreadIds 不为空
      await markAsRead(sessionId)
    }
  },
  { immediate: true }
)

async function handleSend(payload) {
  const sessionId = sessionStore.activeSessionId
  if (!sessionId) return

  await sendMessage(sessionId, payload)
  messageListRef.value?.scrollToBottom(true)
}

async function handleLoadMore() {
  const sessionId = sessionStore.activeSessionId
  if (!sessionId) return

  const prevHeight = messageListRef.value?.saveScrollPosition()
  if (prevHeight == null) return
  const loaded = await loadMore(sessionId)
  if (loaded) {
    messageListRef.value?.restoreScrollPosition(prevHeight)
  }
}

function handleQuote(message) {
  setQuote(message)
}

async function handleRecall(message) {
  const sessionId = sessionStore.activeSessionId
  if (!sessionId) return
  await recallMessage(sessionId, message.id)
}

function handlePreview(url) {
  previewImage.value = url
}

async function handleRetry(message) {
  const sessionId = sessionStore.activeSessionId
  if (!sessionId || !message.clientMsgId) return
  await chatStore.retryMessage(sessionId, message.clientMsgId)
}
</script>

<style lang="scss" scoped>
.chat-area {
  display: flex;
  flex-direction: column;
  height: 100%;
  background-color: var(--bg-chat);

  &__header {
    display: flex;
    align-items: center;
    height: var(--header-height);
    padding: 0 var(--spacing-lg);
    border-bottom: 1px solid var(--border-light);
    background: var(--bg-white);
    flex-shrink: 0;
  }

  &__title {
    font-size: var(--font-size-lg);
    font-weight: 500;
    color: var(--text-primary);
    flex: 1;
  }

  &__group-info-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border: none;
    background: transparent;
    color: var(--text-secondary);
    border-radius: var(--radius-sm);
    cursor: pointer;
    transition: background 150ms, color 150ms;
    flex-shrink: 0;

    &:hover {
      background: var(--bg-hover);
      color: var(--text-primary);
    }
  }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  flex: 1;
  gap: var(--spacing-md);
}

.empty-icon {
  font-size: 64px;
  opacity: 0.4;
}

.empty-text {
  margin: 0;
  font-size: var(--font-size-lg);
  color: var(--text-secondary);
}
</style>
