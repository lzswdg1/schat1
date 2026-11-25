<template>
  <!-- Recalled message -->
  <div v-if="message.recalled" class="message-bubble message-bubble--recalled">
    <span class="message-bubble__recalled-text">该消息已被撤回</span>
  </div>

  <!-- System message: centered, no avatar/bubble -->
  <div v-else-if="message.type === MessageType.SYSTEM" class="message-bubble message-bubble--system">
    <component :is="messageComponentMap[message.type]" :message="message" />
  </div>

  <!-- Normal message -->
  <div
    v-else
    class="message-bubble"
    :class="{ 'message-bubble--self': isSelf, 'message-bubble--other': !isSelf }"
    v-motion
    :initial="{ opacity: 0, x: isSelf ? 24 : -24, scale: 0.94 }"
    :enter="{ opacity: 1, x: 0, scale: 1, transition: { type: 'spring', stiffness: 280, damping: 22 } }"
    @mouseenter="showToolbar = true"
    @mouseleave="showToolbar = false"
  >
    <!-- Avatar (left for others) -->
    <UserAvatar
      v-if="!isSelf"
      :src="displayAvatar"
      :size="36"
      class="message-bubble__avatar"
    />

    <div class="message-bubble__main">
      <!-- Sender name (outside bubble) -->
      <span class="message-bubble__name">{{ displayName }}</span>

      <!-- Bubble content — only wraps the message body -->
      <div class="message-bubble__bubble">
        <!-- Action toolbar on hover -->
        <Transition name="toolbar-fade">
          <div v-show="showToolbar" class="message-bubble__toolbar"
            v-motion
            :initial="{ opacity: 0, y: 8, scale: 0.88 }"
            :enter="{ opacity: 1, y: 0, scale: 1, transition: { type: 'spring', stiffness: 400, damping: 20 } }"
          >
            <button
              class="message-bubble__toolbar-btn"
              title="引用"
              @click="$emit('quote', message)"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
                <path d="M6 17c0-2 2-3 4-3h1V9H7V4h7v10c0 3-2.5 5-5 5H6v-2zm10 0c0-2 2-3 4-3h1V9h-4V4h7v10c0 3-2.5 5-5 5h-3v-2z" />
              </svg>
              <span>引用</span>
            </button>
            <button
              v-if="isSelf"
              class="message-bubble__toolbar-btn"
              title="撤回"
              @click="$emit('recall', message)"
            >
              <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor">
                <path d="M12.5 8c-2.6 0-5 1-6.9 2.6L2 7v9h9l-3.6-3.6A8 8 0 0 1 20 16l2.4-.8A10.5 10.5 0 0 0 12.5 8z" />
              </svg>
              <span>撤回</span>
            </button>
          </div>
        </Transition>

        <!-- Quote block -->
        <div v-if="message.quote" class="message-bubble__quote">
          <span class="message-bubble__quote-sender">{{ message.quote.senderName }}</span>
          <span class="message-bubble__quote-content">{{ message.quote.content }}</span>
        </div>

        <!-- Dynamic message content -->
        <component
          :is="messageComponentMap[message.type]"
          :message="message"
          @preview="(url) => $emit('preview', url)"
        />
      </div>

      <!-- Time + status (outside bubble) -->
      <div class="message-bubble__meta">
        <span class="message-bubble__time">{{ formattedTime }}</span>
        <!-- Send status -->
        <span v-if="isSelf && message.sendStatus === 'sending'" class="message-bubble__status message-bubble__status--sending">
          <svg viewBox="0 0 16 16" width="12" height="12"><circle cx="8" cy="8" r="6" fill="none" stroke="currentColor" stroke-width="2" stroke-dasharray="20" stroke-dashoffset="10"><animateTransform attributeName="transform" type="rotate" from="0 8 8" to="360 8 8" dur="1s" repeatCount="indefinite"/></circle></svg>
        </span>
        <span
          v-else-if="isSelf && message.sendStatus === 'fail'"
          class="message-bubble__status message-bubble__status--fail message-bubble__status--retry"
          title="发送失败，点击重试"
          role="button"
          tabindex="0"
          @click.stop="$emit('retry', message)"
          @keydown.enter.stop="$emit('retry', message)"
        >✕</span>
        <!-- Delivery status (only for successful self messages) -->
        <span v-else-if="isSelf && message.deliveryStatus === 'read'" class="message-bubble__status message-bubble__status--read" title="已读">✓✓</span>
        <span v-else-if="isSelf && message.deliveryStatus === 'delivered'" class="message-bubble__status message-bubble__status--delivered" title="已送达">✓✓</span>
        <span v-else-if="isSelf && (message.deliveryStatus === 'sent' || message.sendStatus === 'success')" class="message-bubble__status message-bubble__status--sent" title="已发送">✓</span>
      </div>
    </div>

    <!-- Avatar (right for self) -->
    <UserAvatar
      v-if="isSelf"
      :src="displayAvatar"
      :size="36"
      class="message-bubble__avatar"
    />
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import UserAvatar from '@/components/common/UserAvatar.vue'
import TextMessage from '@/components/message-types/TextMessage.vue'
import ImageMessage from '@/components/message-types/ImageMessage.vue'
import FileMessage from '@/components/message-types/FileMessage.vue'
import SystemMessage from '@/components/message-types/SystemMessage.vue'
import { MessageType } from '@/constants/messageType.js'
import { useAuthStore } from '@/stores/auth.js'
import { useContactStore } from '@/stores/contact.js'

const props = defineProps({
  message: {
    type: Object,
    required: true
  },
  isSelf: {
    type: Boolean,
    required: true
  }
})

defineEmits(['quote', 'recall', 'preview', 'retry'])

const authStore = useAuthStore()
const contactStore = useContactStore()

const messageComponentMap = {
  [MessageType.TEXT]: TextMessage,
  [MessageType.IMAGE]: ImageMessage,
  [MessageType.FILE]: FileMessage,
  [MessageType.SYSTEM]: SystemMessage
}

const showToolbar = ref(false)

/**
 * 响应式查找发送者昵称：优先用消息自带的，否则从 store 查找
 * 好友列表加载完后会自动更新
 */
const displayName = computed(() => {
  if (props.message.senderName) return props.message.senderName
  const sid = String(props.message.senderId)
  if (sid === String(authStore.user?.id)) return authStore.user?.nickname || ''
  const friend = contactStore.friends.find(f => String(f.id) === sid)
  if (friend?.nickname) return friend.nickname
  // 群成员缓存查找
  const sessionId = props.message.sessionId || ''
  if (sessionId.startsWith('group_')) {
    const groupId = sessionId.replace('group_', '')
    const members = contactStore.groupMembersCache?.[groupId]
    if (members) {
      const member = members.find(m => String(m.userId || m.id) === sid)
      if (member?.nickname) return member.nickname
    }
  }
  return sid
})

const displayAvatar = computed(() => {
  if (props.message.senderAvatar) return props.message.senderAvatar
  const sid = String(props.message.senderId)
  if (sid === String(authStore.user?.id)) return authStore.user?.avatar || ''
  const friend = contactStore.friends.find(f => String(f.id) === sid)
  if (friend?.avatar) return friend.avatar
  // 群成员缓存查找
  const sessionId = props.message.sessionId || ''
  if (sessionId.startsWith('group_')) {
    const groupId = sessionId.replace('group_', '')
    const members = contactStore.groupMembersCache?.[groupId]
    if (members) {
      const member = members.find(m => String(m.userId || m.id) === sid)
      if (member?.avatar) return member.avatar
    }
  }
  return ''
})

const formattedTime = computed(() => {
  const date = new Date(props.message.timestamp)
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  return `${hours}:${minutes}`
})
</script>

<style lang="scss" scoped>
.message-bubble {
  display: flex;
  align-items: flex-start;
  padding: 8px 16px;
  position: relative;

  &--recalled {
    justify-content: center;
    padding: 2px 16px;
  }

  &__recalled-text {
    font-size: 12px;
    color: var(--text-system);
  }

  &--system {
    justify-content: center;
    padding: 2px 16px;
  }

  // --- Other (left-aligned) ---
  &--other {
    flex-direction: row;

    .message-bubble__bubble {
      background: #fff;
      border-radius: 0 8px 8px 8px;
    }

    .message-bubble__toolbar {
      left: 100%;
      margin-left: 4px;
    }
  }

  // --- Self (right-aligned) ---
  &--self {
    flex-direction: row;
    justify-content: flex-end;

    .message-bubble__main {
      align-items: flex-end;
    }

    .message-bubble__bubble {
      background: #a0e75a;
      border-radius: 8px 0 8px 8px;
    }

    .message-bubble__name {
      text-align: right;
    }

    .message-bubble__toolbar {
      right: 100%;
      margin-right: 4px;
    }
  }

  &__avatar {
    margin: 0 8px;
    flex-shrink: 0;
    margin-top: 18px; // 对齐气泡（跳过昵称行）
  }

  &__main {
    display: flex;
    flex-direction: column;
    max-width: 60%;
    position: relative;
  }

  // 昵称在气泡外上方
  &__name {
    font-size: 12px;
    color: #999;
    margin-bottom: 2px;
    line-height: 1;
  }

  // 气泡只包裹内容，inline 宽度
  &__bubble {
    display: inline-block;
    position: relative;
    padding: 9px 14px;
    word-wrap: break-word;
    overflow-wrap: break-word;
    line-height: 1.4;
    font-size: 14px;
  }

  // 时间 + 状态在气泡外下方
  &__meta {
    display: flex;
    align-items: center;
    gap: 4px;
    margin-top: 2px;
  }

  &__time {
    font-size: 11px;
    color: #bbb;
    line-height: 1;
  }

  &__status {
    font-size: 11px;
    line-height: 1;

    &--sending {
      color: #bbb;
    }

    &--fail {
      color: var(--text-danger);
      font-weight: 600;
    }

    &--retry {
      cursor: pointer;
      user-select: none;
      &:hover {
        opacity: 0.7;
      }
    }

    &--sent {
      color: #bbb;
    }

    &--delivered {
      color: #bbb;
      letter-spacing: -2px;
    }

    &--read {
      color: #07c160;
      letter-spacing: -2px;
    }
  }

  // --- Quote block ---
  &__quote {
    display: flex;
    flex-direction: column;
    gap: 2px;
    padding: 4px 8px;
    margin-bottom: 6px;
    border-left: 2px solid var(--color-primary);
    background: rgba(0, 0, 0, 0.03);
    border-radius: 0 4px 4px 0;
  }

  &__quote-sender {
    font-size: 11px;
    color: var(--text-link);
    font-weight: 500;
  }

  &__quote-content {
    font-size: 12px;
    color: #999;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    max-width: 200px;
  }

  // --- Action toolbar ---
  &__toolbar {
    position: absolute;
    top: 0;
    display: flex;
    gap: 2px;
    background: #fff;
    border-radius: 6px;
    box-shadow: 0 2px 12px rgba(0, 0, 0, 0.1);
    padding: 3px;
    z-index: var(--z-dropdown);
  }

  &__toolbar-btn {
    display: flex;
    align-items: center;
    gap: 3px;
    padding: 4px 8px;
    border: none;
    background: transparent;
    color: #666;
    font-size: 12px;
    cursor: pointer;
    border-radius: 4px;
    white-space: nowrap;
    transition: background 150ms ease, color 150ms ease;

    &:hover {
      background: rgba(0, 0, 0, 0.04);
      color: #333;
    }

    &:active {
      background: rgba(0, 0, 0, 0.08);
    }

    svg {
      flex-shrink: 0;
    }
  }
}

.toolbar-fade-enter-active,
.toolbar-fade-leave-active {
  transition: opacity 120ms ease, transform 120ms ease;
}

.toolbar-fade-enter-from,
.toolbar-fade-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
</style>
