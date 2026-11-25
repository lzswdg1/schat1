/**
 * 消息气泡属性测试
 * Properties 13, 14, 20, 21
 */

import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MessageBubble from '@/components/chat/MessageBubble.vue'
import { MessageType } from '@/constants/messageType.js'

const currentUserId = 'user_1'

function createMessage(overrides = {}) {
  return {
    id: 'msg_1',
    sessionId: 'session_1',
    type: MessageType.TEXT,
    senderId: 'user_2',
    senderName: '李四',
    senderAvatar: 'https://example.com/avatar.png',
    content: '测试消息',
    timestamp: Date.now(),
    recalled: false,
    quote: null,
    fileMeta: null,
    ...overrides
  }
}

describe('MessageBubble Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Feature: web-im-system, Property 13: 消息对齐方向由发送者决定
  it('isSelf should be true when senderId equals currentUserId', () => {
    fc.assert(
      fc.property(
        fc.constantFrom(currentUserId, 'user_2', 'user_3', 'user_4'),
        (senderId) => {
          const isSelf = senderId === currentUserId
          const msg = createMessage({ senderId })

          const wrapper = mount(MessageBubble, {
            props: { message: msg, isSelf },
            global: { plugins: [createPinia()] }
          })

          if (isSelf) {
            expect(wrapper.find('.message-bubble--self').exists()).toBe(true)
          } else {
            expect(wrapper.find('.message-bubble--other').exists()).toBe(true)
          }

          wrapper.unmount()
        }
      ),
      { numRuns: 20 }
    )
  })

  // Feature: web-im-system, Property 14: 消息气泡包含必要信息
  it('message bubble should contain avatar, sender name, and time', () => {
    const msg = createMessage()
    const wrapper = mount(MessageBubble, {
      props: { message: msg, isSelf: false },
      global: { plugins: [createPinia()] }
    })

    // Should have avatar
    expect(wrapper.find('.message-bubble__avatar').exists()).toBe(true)
    // Should have sender name
    expect(wrapper.find('.message-bubble__name').text()).toBe(msg.senderName)
    // Should have time
    expect(wrapper.find('.message-bubble__time').exists()).toBe(true)

    wrapper.unmount()
  })

  // Feature: web-im-system, Property 20: 引用消息渲染包含摘要块
  it('message with quote should render quote block with sender and content', () => {
    const msg = createMessage({
      quote: {
        messageId: 'msg_quoted',
        senderName: '王五',
        content: '被引用的消息'
      }
    })

    const wrapper = mount(MessageBubble, {
      props: { message: msg, isSelf: false },
      global: { plugins: [createPinia()] }
    })

    const quoteBlock = wrapper.find('.message-bubble__quote')
    expect(quoteBlock.exists()).toBe(true)
    expect(wrapper.find('.message-bubble__quote-sender').text()).toBe('王五')
    expect(wrapper.find('.message-bubble__quote-content').text()).toBe('被引用的消息')

    wrapper.unmount()
  })

  // Feature: web-im-system, Property 21: 仅允许撤回自己的消息
  it('recall button should only appear on self messages', async () => {
    // Self message: should have recall button (on hover)
    const selfMsg = createMessage({ senderId: currentUserId })
    const selfWrapper = mount(MessageBubble, {
      props: { message: selfMsg, isSelf: true },
      global: { plugins: [createPinia()] }
    })

    // Trigger hover to show toolbar
    await selfWrapper.find('.message-bubble').trigger('mouseenter')
    const recallBtns = selfWrapper.findAll('.message-bubble__toolbar-btn')
    const hasRecall = recallBtns.some(btn => btn.text().includes('撤回'))
    expect(hasRecall).toBe(true)
    selfWrapper.unmount()

    // Other's message: should NOT have recall button
    const otherMsg = createMessage({ senderId: 'user_2' })
    const otherWrapper = mount(MessageBubble, {
      props: { message: otherMsg, isSelf: false },
      global: { plugins: [createPinia()] }
    })

    await otherWrapper.find('.message-bubble').trigger('mouseenter')
    const otherBtns = otherWrapper.findAll('.message-bubble__toolbar-btn')
    const otherHasRecall = otherBtns.some(btn => btn.text().includes('撤回'))
    expect(otherHasRecall).toBe(false)
    otherWrapper.unmount()
  })
})
