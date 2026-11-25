<template>
  <div class="message-search">
    <div class="message-search__input-wrap">
      <input
        v-model="keyword"
        class="message-search__input"
        type="text"
        placeholder="搜索聊天记录"
        @input="handleInput"
      />
      <button v-if="keyword" class="message-search__clear" @click="clearSearch">✕</button>
    </div>

    <div v-if="loading" class="message-search__status">搜索中...</div>

    <div v-else-if="searched && !results.length" class="message-search__status">
      未找到相关消息
    </div>

    <div v-else-if="results.length" class="message-search__results">
      <div
        v-for="item in results"
        :key="resultKey(item)"
        class="search-result-item"
        @click="handleNavigate(item)"
      >
        <div class="search-result-item__session">{{ item.sessionName }}</div>
        <div class="search-result-item__content">
          <template v-for="(part, index) in highlightKeyword(item.content)" :key="index">
            <mark v-if="part.matched">{{ part.text }}</mark>
            <template v-else>{{ part.text }}</template>
          </template>
        </div>
        <div class="search-result-item__meta">
          <span class="search-result-item__sender">{{ item.senderName }}</span>
          <span class="search-result-item__time">{{ formatTime(item.createTime) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'
import { useSessionStore } from '@/stores/session.js'
import { useChatStore } from '@/stores/chat.js'
import * as chatDB from '@/db/chatDB.js'
import { searchMessages as apiSearchMessages } from '@/api/chat.js'

const emit = defineEmits(['navigate'])

const sessionStore = useSessionStore()
const chatStore = useChatStore()

const keyword = ref('')
const results = ref([])
const loading = ref(false)
const searched = ref(false)

let debounceTimer = null
let searchVersion = 0
let pendingRecalls = null

const unsubscribeRecall = chatStore.$onAction(({ name, args, after }) => {
  if (name !== 'applyRecallProjection' || !args[1]?.recalled) return
  after(() => {
    const key = resultKey({ sessionId: args[0], messageId: args[1].id })
    pendingRecalls?.add(key)
    results.value = results.value.filter(result => resultKey(result) !== key)
  })
})

function resultKey(result) {
  return JSON.stringify([String(result.sessionId), String(result.messageId)])
}

function handleInput() {
  searchVersion++
  pendingRecalls = null
  loading.value = false
  searched.value = false
  results.value = []
  if (debounceTimer) clearTimeout(debounceTimer)
  if (!keyword.value.trim()) {
    results.value = []
    return
  }
  debounceTimer = setTimeout(() => doSearch(), 300)
}

async function doSearch() {
  const kw = keyword.value.trim()
  if (!kw) return
  const version = ++searchVersion
  const recalledDuringSearch = new Set()
  pendingRecalls = recalledDuringSearch

  loading.value = true
  searched.value = false

  try {
    // 本地优先
    const localResults = await chatDB.searchMessages(kw, 50)
    if (version !== searchVersion) return

    // 构建 sessionId → sessionName 映射
    const nameMap = {}
    for (const s of sessionStore.sessions) {
      nameMap[s.id] = s.targetName
    }

    const localMapped = localResults.map(m => ({
      sessionId: String(m.sessionId),
      messageId: String(m.id),
      fromId: m.senderId == null ? '' : String(m.senderId),
      senderName: m.senderName,
      content: m.content,
      createTime: m.timestamp,
      sessionName: nameMap[m.sessionId] || m.sessionId
    }))

    // 本地不足 50 条时，API 补充
    let merged = localMapped
    if (localMapped.length < 50) {
      try {
        const res = await apiSearchMessages(kw, 50)
        if (res.code === 200 && Array.isArray(res.data)) {
          const seen = new Set(localMapped.map(resultKey))
          for (const result of res.data) {
            if (seen.has(resultKey(result))) continue
            seen.add(resultKey(result))
            merged.push({
              ...result,
              sessionId: String(result.sessionId),
              messageId: String(result.messageId),
              fromId: String(result.fromId),
              senderName: result.senderName || String(result.fromId),
              sessionName: nameMap[result.sessionId] || result.sessionName || String(result.sessionId)
            })
          }
        }
      } catch {
        // API 失败不影响本地结果
      }
    }

    merged = await chatDB.excludeRecalledSearchResults(merged)
    if (version !== searchVersion) return
    merged.sort((a, b) => b.createTime - a.createTime)
    results.value = merged.filter(result => !recalledDuringSearch.has(resultKey(result))).slice(0, 50)
  } catch {
    if (version === searchVersion) results.value = []
  } finally {
    if (version === searchVersion) {
      loading.value = false
      searched.value = true
      pendingRecalls = null
    }
  }
}

function clearSearch() {
  searchVersion++
  pendingRecalls = null
  keyword.value = ''
  loading.value = false
  results.value = []
  searched.value = false
  if (debounceTimer) clearTimeout(debounceTimer)
}

function handleNavigate(item) {
  sessionStore.setActiveSession(item.sessionId)
  sessionStore.clearUnread(item.sessionId)
  emit('navigate', item)
}

function highlightKeyword(text) {
  const content = String(text ?? '')
  if (!keyword.value.trim()) return [{ text: content, matched: false }]
  const kw = keyword.value.trim()
  // 手动转义正则特殊字符
  let escaped = ''
  for (const ch of kw) {
    if ('.*+?^${}()|[]\\'.includes(ch)) escaped += '\\' + ch
    else escaped += ch
  }
  const regex = new RegExp(`(${escaped})`, 'gi')
  return content.split(regex).map((part, index) => ({ text: part, matched: index % 2 === 1 }))
}

function formatTime(timestamp) {
  if (!timestamp) return ''
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
  return `${month}/${day} ${time}`
}

defineExpose({ clearSearch })

onBeforeUnmount(() => {
  unsubscribeRecall()
  searchVersion++
  if (debounceTimer) clearTimeout(debounceTimer)
})
</script>

<style lang="scss" scoped>
.message-search {
  padding: var(--spacing-md);

  &__input-wrap {
    position: relative;
    display: flex;
    align-items: center;
  }

  &__input {
    width: 100%;
    padding: var(--spacing-sm) var(--spacing-md);
    padding-right: 32px;
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

  &__clear {
    position: absolute;
    right: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 22px;
    height: 22px;
    padding: 0;
    font-size: 12px;
    color: var(--text-secondary);
    background: transparent;
    border: none;
    border-radius: 50%;
    cursor: pointer;

    &:hover {
      background-color: var(--bg-hover);
      color: var(--text-primary);
    }
  }

  &__status {
    padding: var(--spacing-md) 0;
    text-align: center;
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
  }

  &__results {
    margin-top: var(--spacing-sm);
    max-height: 400px;
    overflow-y: auto;
  }
}

.search-result-item {
  padding: var(--spacing-sm) var(--spacing-md);
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: background-color 200ms ease;

  & + & {
    margin-top: 2px;
  }

  &:hover {
    background-color: var(--bg-hover);
  }

  &__session {
    font-size: var(--font-size-sm);
    font-weight: 500;
    color: var(--text-primary);
    margin-bottom: 2px;
  }

  &__content {
    font-size: var(--font-size-sm);
    color: var(--text-secondary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;

    :deep(mark) {
      background-color: rgba(7, 193, 96, 0.25);
      color: inherit;
      padding: 0 1px;
      border-radius: 2px;
    }
  }

  &__meta {
    display: flex;
    justify-content: space-between;
    margin-top: 2px;
    font-size: var(--font-size-xs);
    color: var(--text-placeholder);
  }

  &__sender {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__time {
    flex-shrink: 0;
    margin-left: var(--spacing-sm);
  }
}
</style>
