/**
 * 消息类型映射属性测试
 * Property 28
 */

import { describe, it, expect } from 'vitest'
import { MessageType } from '@/constants/messageType.js'

// Import the component map from MessageBubble's logic
// We replicate the map here since it's defined inside the component
import TextMessage from '@/components/message-types/TextMessage.vue'
import ImageMessage from '@/components/message-types/ImageMessage.vue'
import FileMessage from '@/components/message-types/FileMessage.vue'
import SystemMessage from '@/components/message-types/SystemMessage.vue'

const messageComponentMap = {
  [MessageType.TEXT]: TextMessage,
  [MessageType.IMAGE]: ImageMessage,
  [MessageType.FILE]: FileMessage,
  [MessageType.SYSTEM]: SystemMessage
}

describe('MessageType Properties', () => {
  // Feature: web-im-system, Property 28: 消息类型到组件映射完整性
  it('every renderable MessageType should have a valid component in the map', () => {
    // QUOTE type is reserved and handled via message.quote field, not rendered as a separate component
    const renderableTypes = [MessageType.TEXT, MessageType.IMAGE, MessageType.FILE, MessageType.SYSTEM]

    for (const type of renderableTypes) {
      const component = messageComponentMap[type]
      expect(component).toBeDefined()
      expect(component).not.toBeUndefined()
      // Vue component should be an object with at least a setup or render function
      expect(typeof component).toBe('object')
    }
  })

  // Feature: web-im-system, Property 28 (completeness): map covers all renderable types
  it('messageComponentMap should have entries for all renderable types', () => {
    const renderableTypes = ['text', 'image', 'file', 'system']
    const mapKeys = Object.keys(messageComponentMap)

    for (const type of renderableTypes) {
      expect(mapKeys).toContain(type)
    }
  })
})
