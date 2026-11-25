/**
 * 分页与历史消息属性测试
 * Properties 27, 31
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { resetDB } from '@/api/mock/data.js'

describe('Pagination & History Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetDB()
  })

  // Feature: web-im-system, Property 31: 历史消息加载后消息列表包含新数据
  it('fetchHistory should populate store with all returned messages', async () => {
    const { useChatStore } = await import('@/stores/chat.js')
    const store = useChatStore()
    const sessionId = 'session_1_2'

    const res = await store.fetchHistory(sessionId)
    expect(res.code).toBe(200)

    const storeMessages = store.messages[sessionId]
    expect(storeMessages).toBeDefined()
    expect(storeMessages.length).toBeGreaterThanOrEqual(res.data.messages.length)

    // Every message from API should be in the store
    for (const apiMsg of res.data.messages) {
      const found = storeMessages.find(m => m.id === apiMsg.id)
      expect(found).toBeDefined()
    }
  })

  // Feature: web-im-system, Property 27: 分页历史消息正确性 (store-level)
  it('multiple fetchHistory calls should accumulate messages correctly', async () => {
    const { useChatStore } = await import('@/stores/chat.js')
    const store = useChatStore()
    const sessionId = 'session_1_2'

    // First fetch with small page size
    await store.fetchHistory(sessionId)
    const firstLen = store.messages[sessionId].length

    // If there are more, fetch again
    if (store.hasMore[sessionId]) {
      const cursor = store.cursors[sessionId]
      await store.fetchHistory(sessionId, cursor)
      // Messages should have grown
      expect(store.messages[sessionId].length).toBeGreaterThan(firstLen)
    }
  })
})
