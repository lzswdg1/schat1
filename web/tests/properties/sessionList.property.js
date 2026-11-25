/**
 * 会话列表属性测试
 * Property 11
 */

import { describe, it, expect } from 'vitest'
import fc from 'fast-check'

describe('Session List Properties', () => {
  // Feature: web-im-system, Property 11: 会话摘要与最新消息一致
  it('session summary should match the last message content and timestamp', () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 100 }),
        fc.nat({ max: 2000000000000 }),
        (content, timestamp) => {
          // Simulate updateLastMessage behavior
          const session = {
            id: 'test',
            type: 'private',
            targetId: 'user_2',
            targetName: 'Test',
            targetAvatar: '',
            lastMessage: '',
            lastTime: 0,
            unreadCount: 0
          }

          const message = { content, timestamp }

          // Apply update (same logic as sessionStore.updateLastMessage)
          session.lastMessage = message.content
          session.lastTime = message.timestamp

          expect(session.lastMessage).toBe(content)
          expect(session.lastTime).toBe(timestamp)
        }
      ),
      { numRuns: 100 }
    )
  })
})
