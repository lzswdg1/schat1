/**
 * Store 属性测试 — Chat Store
 * Properties 12, 18, 22
 */

import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import { createPinia, setActivePinia } from 'pinia'
import { resetDB } from '@/api/mock/data.js'
import { useChatStore } from '@/stores/chat.js'
import * as chatDB from '@/db/chatDB.js'

describe('Chat Store Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    resetDB()
  })

  // Feature: web-im-system, Property 12: 发送消息追加到列表末尾
  it('sendMessage should append message to end of list', async () => {
    const store = useChatStore()
    const sessionId = 'session_1_2'

    // Initialize messages
    store.messages[sessionId] = []
    const beforeLen = store.messages[sessionId].length

    const res = await store.sendMessage(sessionId, {
      type: 'text',
      content: 'hello test',
      quoteId: undefined
    })

    expect(res.code).toBe(200)
    expect(store.messages[sessionId].length).toBe(beforeLen + 1)
    const lastMsg = store.messages[sessionId][store.messages[sessionId].length - 1]
    expect(lastMsg.content).toBe('hello test')
  })

  // Feature: web-im-system, Property 18: 引用状态设置正确性
  it('setQuote should store correct quote message info', () => {
    fc.assert(
      fc.property(
        fc.record({
          id: fc.uuid(),
          senderName: fc.string({ minLength: 1, maxLength: 10 }),
          content: fc.string({ minLength: 1, maxLength: 100 })
        }),
        (msg) => {
          // Use the store from beforeEach (same pinia)
          const store = useChatStore()
          store.setQuote(msg)

          expect(store.quoteMessage).not.toBeNull()
          expect(store.quoteMessage.id).toBe(msg.id)
          expect(store.quoteMessage.senderName).toBe(msg.senderName)
          expect(store.quoteMessage.content).toBe(msg.content)
        }
      ),
      { numRuns: 50 }
    )
  })

  // Feature: web-im-system, Property 22: 撤回后消息标记为已撤回
  it('recallMessage should set recalled to true', async () => {
    const store = useChatStore()

    // Load history first to get real messages
    await store.fetchHistory('session_1_2')
    const msgs = store.messages['session_1_2']
    expect(msgs.length).toBeGreaterThan(0)

    const targetMsg = msgs[0]
    const res = await store.recallMessage('session_1_2', targetMsg.id)
    expect(res.code).toBe(200)

    const updated = store.messages['session_1_2'].find(m => m.id === targetMsg.id)
    expect(updated.recalled).toBe(true)
  })

  it('new group without local messages should still be incrementally synchronized', async () => {
    const store = useChatStore()
    await chatDB.deleteSessionMessages('session_g1')

    const result = await store.syncOfflineGroupMessages(null, ['session_g1'])
    const persisted = await chatDB.getRecentMessages('session_g1', 50)

    expect(result.synced).toBe(1)
    expect(persisted.length).toBeGreaterThan(0)
    expect(persisted[0].sessionId).toBe('session_g1')
  })
})
