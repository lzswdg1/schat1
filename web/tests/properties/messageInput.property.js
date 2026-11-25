/**
 * 消息输入框属性测试
 * Properties 15, 19
 */

import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import MessageInput from '@/components/chat/MessageInput.vue'
import { useChatStore } from '@/stores/chat.js'

describe('MessageInput Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Feature: web-im-system, Property 15: 空输入禁用发送
  it('send button should be disabled when input is empty or whitespace-only', () => {
    fc.assert(
      fc.property(
        fc.constantFrom('', ' ', '  ', '\t', '\n', '  \n  ', '\t\n'),
        (whitespace) => {
          const pinia = createPinia()
          setActivePinia(pinia)

          const wrapper = mount(MessageInput, {
            props: { sessionId: 'session_1' },
            global: { plugins: [pinia] }
          })

          const textarea = wrapper.find('textarea')
          textarea.setValue(whitespace)

          const sendBtn = wrapper.find('.message-input__send-btn')
          // In Vue test utils, disabled boolean attribute is present as empty string
          expect(sendBtn.element.disabled).toBe(true)

          wrapper.unmount()
        }
      ),
      { numRuns: 7 }
    )
  })

  // Feature: web-im-system, Property 15 (positive): non-empty input enables send
  it('send button should be enabled when input has non-whitespace content', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)

    const wrapper = mount(MessageInput, {
      props: { sessionId: 'session_1' },
      global: { plugins: [pinia] }
    })

    const textarea = wrapper.find('textarea')
    await textarea.setValue('hello')
    await wrapper.vm.$nextTick()

    const sendBtn = wrapper.find('.message-input__send-btn')
    expect(sendBtn.element.disabled).toBe(false)

    wrapper.unmount()
  })

  // Feature: web-im-system, Property 19: 引用消息附加到发送数据
  it('message sent with active quote should include quoteId', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)

    const chatStore = useChatStore()

    // Set a quote
    chatStore.setQuote({
      id: 'msg_quoted_123',
      senderName: '王五',
      content: '被引用的消息'
    })

    const wrapper = mount(MessageInput, {
      props: { sessionId: 'session_1' },
      global: { plugins: [pinia] }
    })

    // Type a message
    await wrapper.find('textarea').setValue('回复消息')

    // Click send
    await wrapper.find('.message-input__send-btn').trigger('click')

    // Check emitted event
    const sendEvents = wrapper.emitted('send')
    expect(sendEvents).toBeTruthy()
    expect(sendEvents.length).toBeGreaterThan(0)

    const payload = sendEvents[0][0]
    expect(payload.quoteId).toBe('msg_quoted_123')

    wrapper.unmount()
  })
})
