/**
 * Store 属性测试 — Session Store
 * Properties 8, 9, 10
 */

import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import { createPinia, setActivePinia } from 'pinia'

// Session generator for property tests
const sessionArb = fc.record({
  id: fc.uuid(),
  type: fc.constantFrom('private', 'group'),
  targetId: fc.uuid(),
  targetName: fc.string({ minLength: 1, maxLength: 10 }),
  targetAvatar: fc.constant('https://example.com/avatar.png'),
  lastMessage: fc.string({ maxLength: 50 }),
  lastTime: fc.nat({ max: 2000000000000 }),
  unreadCount: fc.nat({ max: 999 })
})

describe('Session Store Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Feature: web-im-system, Property 8: 会话分类正确性
  it('privateSessions and groupSessions should partition sessions correctly', async () => {
    const { useSessionStore } = await import('@/stores/session.js')

    fc.assert(
      fc.property(
        fc.array(sessionArb, { maxLength: 30 }),
        (sessions) => {
          const store = useSessionStore()
          store.sessions = sessions

          const priv = store.privateSessions
          const group = store.groupSessions

          // All private sessions have type 'private'
          expect(priv.every(s => s.type === 'private')).toBe(true)
          // All group sessions have type 'group'
          expect(group.every(s => s.type === 'group')).toBe(true)
          // Union equals original
          expect(priv.length + group.length).toBe(sessions.length)
        }
      ),
      { numRuns: 100 }
    )
  })

  // Feature: web-im-system, Property 9: 折叠状态切换往返
  it('double toggleCollapse should restore initial state', async () => {
    const { useSessionStore } = await import('@/stores/session.js')

    fc.assert(
      fc.property(
        fc.constantFrom('private', 'group'),
        fc.boolean(),
        (groupType, initialState) => {
          const store = useSessionStore()
          store.collapsedGroups[groupType] = initialState

          store.toggleCollapse(groupType)
          store.toggleCollapse(groupType)

          expect(store.collapsedGroups[groupType]).toBe(initialState)
        }
      ),
      { numRuns: 100 }
    )
  })

  // Feature: web-im-system, Property 10: 分类未读消息计数
  it('unread count should equal sum of unreadCount in each category', async () => {
    const { useSessionStore } = await import('@/stores/session.js')

    fc.assert(
      fc.property(
        fc.array(sessionArb, { maxLength: 30 }),
        (sessions) => {
          const store = useSessionStore()
          store.sessions = sessions

          const expectedPrivate = sessions
            .filter(s => s.type === 'private')
            .reduce((sum, s) => sum + s.unreadCount, 0)
          const expectedGroup = sessions
            .filter(s => s.type === 'group')
            .reduce((sum, s) => sum + s.unreadCount, 0)

          expect(store.privateUnreadCount).toBe(expectedPrivate)
          expect(store.groupUnreadCount).toBe(expectedGroup)
        }
      ),
      { numRuns: 100 }
    )
  })
})
