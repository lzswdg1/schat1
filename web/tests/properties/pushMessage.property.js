/**
 * 推送消息属性测试
 * Property 26
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { MessageType } from '@/constants/messageType.js'

describe('Push Message Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Feature: web-im-system, Property 26: 推送消息更新 Store
  it('appendMessage should add pushed message to the correct session in chatStore', async () => {
    const { useChatStore } = await import('@/stores/chat.js')
    const store = useChatStore()

    const sessionId = 'session_push_test'
    const pushedMessage = {
      id: 'msg_push_1',
      sessionId,
      type: MessageType.TEXT,
      senderId: 'user_2',
      senderName: '李四',
      senderAvatar: 'https://example.com/avatar.png',
      content: '推送的消息',
      timestamp: Date.now(),
      recalled: false,
      quote: null,
      fileMeta: null
    }

    // Append message (simulating push)
    await store.appendMessage(sessionId, pushedMessage)

    // Verify it's in the store
    expect(store.messages[sessionId]).toBeDefined()
    const found = store.messages[sessionId].find(m => m.id === pushedMessage.id)
    expect(found).toBeDefined()
    expect(found.content).toBe('推送的消息')
    expect(found.senderId).toBe('user_2')
  })

  // Feature: web-im-system, Property 26 (multiple pushes)
  it('multiple pushed messages should all be present in store', async () => {
    const { useChatStore } = await import('@/stores/chat.js')
    const store = useChatStore()

    const sessionId = 'session_multi_push'

    for (let i = 0; i < 5; i++) {
      await store.appendMessage(sessionId, {
        id: `msg_push_${i}`,
        sessionId,
        type: MessageType.TEXT,
        senderId: 'user_2',
        senderName: '李四',
        senderAvatar: '',
        content: `消息 ${i}`,
        timestamp: Date.now() + i,
        recalled: false,
        quote: null,
        fileMeta: null
      })
    }

    expect(store.messages[sessionId].length).toBe(5)
    for (let i = 0; i < 5; i++) {
      expect(store.messages[sessionId][i].content).toBe(`消息 ${i}`)
    }
  })
})
