<template>
  <div
    ref="listRef"
    class="message-list"
    @scroll="handleScroll"
  >
    <!-- 顶部哨兵元素：Intersection Observer 触发加载更多 -->
    <div ref="sentinelRef" class="message-list__sentinel">
      <span v-if="loadingMore" class="message-list__loading">加载中...</span>
    </div>

    <!-- 消息列表 -->
    <div class="message-list__messages">
      <template v-for="(msg, index) in messages" :key="msg.id">
        <!-- 时间分割线 -->
        <div v-if="shouldShowTimeDivider(index)" class="message-list__time-divider">
          <span class="message-list__time-label">{{ formatTimeDivider(msg.timestamp) }}</span>
        </div>
        <MessageBubble
          :message="msg"
          :is-self="String(msg.senderId) === String(authStore.user?.id)"
          @quote="$emit('quote', $event)"
          @recall="$emit('recall', $event)"
          @preview="$emit('preview', $event)"
          @retry="$emit('retry', $event)"
        />
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import MessageBubble from './MessageBubble.vue'
import { useChatStore } from '@/stores/chat.js'
import { useAuthStore } from '@/stores/auth.js'
import { useScroll } from '@/composables/useScroll.js'

const props = defineProps({
  sessionId: {
    type: String,
    required: true
  },
  loadingMore: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['quote', 'recall', 'preview', 'load-more', 'retry'])

const chatStore = useChatStore()
const authStore = useAuthStore()

const listRef = ref(null)
const sentinelRef = ref(null)

const { isAtBottom, checkAtBottom, scrollToBottom, saveScrollPosition, restoreScrollPosition } = useScroll(listRef)

const messages = computed(() => chatStore.messages[props.sessionId] || [])

const TIME_DIVIDER_THRESHOLD = 5 * 60 * 1000 // 5 分钟

function shouldShowTimeDivider(index) {
  if (index === 0) return true
  const curr = messages.value[index]
  const prev = messages.value[index - 1]
  if (!curr?.timestamp || !prev?.timestamp) return false
  return curr.timestamp - prev.timestamp > TIME_DIVIDER_THRESHOLD
}

function formatTimeDivider(timestamp) {
  const date = new Date(timestamp)
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const target = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const time = `${hours}:${minutes}`

  if (target.getTime() === today.getTime()) return time
  if (target.getTime() === today.getTime() - 86400000) return `昨天 ${time}`

  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  if (date.getFullYear() === now.getFullYear()) return `${month}/${day} ${time}`
  return `${date.getFullYear()}/${month}/${day} ${time}`
}

let observer = null

onMounted(() => {
  setupObserver()
  scrollToBottom()
})

onBeforeUnmount(() => {
  cleanupObserver()
})

function setupObserver() {
  if (!sentinelRef.value || !listRef.value) return

  observer = new IntersectionObserver(
    (entries) => {
      const entry = entries[0]
      if (entry.isIntersecting && !props.loadingMore && chatStore.hasMore[props.sessionId]) {
        emit('load-more')
      }
    },
    {
      root: listRef.value,
      threshold: 0
    }
  )

  observer.observe(sentinelRef.value)
}

function cleanupObserver() {
  if (observer) {
    observer.disconnect()
    observer = null
  }
}

function handleScroll() {
  checkAtBottom()
}

// 监听消息列表变化，如果用户在底部则自动滚动
watch(
  () => messages.value.length,
  (newLen, oldLen) => {
    if (newLen > oldLen && isAtBottom.value) {
      scrollToBottom(true)
    }
  }
)

// 监听 sessionId 变化，切换会话时滚动到底部
watch(
  () => props.sessionId,
  () => {
    nextTick(() => scrollToBottom())
  }
)

// 暴露滚动锚定方法给父组件
defineExpose({
  saveScrollPosition,
  restoreScrollPosition,
  scrollToBottom
})
</script>

<style lang="scss" scoped>
.message-list {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  padding: var(--spacing-sm) 0;

  &__sentinel {
    display: flex;
    justify-content: center;
    padding: var(--spacing-sm);
    min-height: 1px;
  }

  &__loading {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
  }

  &__time-divider {
    display: flex;
    justify-content: center;
    padding: var(--spacing-sm) 0;
  }

  &__time-label {
    font-size: 11px;
    color: #bbb;
    background: rgba(0, 0, 0, 0.04);
    padding: 2px 10px;
    border-radius: 10px;
  }
}
</style>
