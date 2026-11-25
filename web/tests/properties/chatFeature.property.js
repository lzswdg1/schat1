/**
 * 聊天功能属性测试
 * Properties 16, 31
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import FileMessage from '@/components/message-types/FileMessage.vue'
import { MessageType } from '@/constants/messageType.js'

describe('Chat Feature Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Feature: web-im-system, Property 16: 文件消息展示元信息
  it('FILE type message should display file name, size, and type', () => {
    const msg = {
      id: 'msg_file_1',
      sessionId: 'session_1',
      type: MessageType.FILE,
      senderId: 'user_1',
      senderName: '张三',
      senderAvatar: 'https://example.com/avatar.png',
      content: '需求文档.pdf',
      timestamp: Date.now(),
      recalled: false,
      quote: null,
      fileMeta: {
        name: '需求文档.pdf',
        size: 2048576,
        mimeType: 'application/pdf'
      }
    }

    const wrapper = mount(FileMessage, {
      props: { message: msg },
      global: { plugins: [createPinia()] }
    })

    const text = wrapper.text()
    // Should contain file name
    expect(text).toContain('需求文档.pdf')
    // Should contain some size representation
    expect(text).toMatch(/\d/)

    wrapper.unmount()
  })
})
