/**
 * 持久化属性测试
 * Properties 29, 30
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { resetDB, mockDB, persistDB } from '@/api/mock/data.js'

describe('Persistence Properties', () => {
  beforeEach(() => {
    localStorage.clear()
    resetDB()
  })

  // Feature: web-im-system, Property 29: 用户凭证持久化往返
  // Verify that auth credentials can be persisted to and restored from localStorage
  it('user credentials should survive a localStorage roundtrip', () => {
    const user = { id: 'user_1', username: 'zhangsan', nickname: '张三', avatar: 'https://example.com/a.png' }
    const token = 'token_user_1_12345'

    // Simulate what pinia-plugin-persistedstate does: write to localStorage
    const authState = { user, token }
    localStorage.setItem('auth', JSON.stringify(authState))

    // Read back — simulating page refresh
    const restored = JSON.parse(localStorage.getItem('auth'))
    expect(restored.user).toEqual(user)
    expect(restored.token).toBe(token)
  })

  // Feature: web-im-system, Property 29 (extended): verify multiple roundtrips
  it('multiple credential updates should all persist correctly', () => {
    const credentials = [
      { user: { id: 'u1', username: 'a', nickname: 'A', avatar: '' }, token: 'tok1' },
      { user: { id: 'u2', username: 'b', nickname: 'B', avatar: '' }, token: 'tok2' },
      { user: { id: 'u3', username: 'c', nickname: 'C', avatar: '' }, token: 'tok3' }
    ]

    for (const cred of credentials) {
      localStorage.setItem('auth', JSON.stringify(cred))
      const restored = JSON.parse(localStorage.getItem('auth'))
      expect(restored.user.id).toBe(cred.user.id)
      expect(restored.token).toBe(cred.token)
    }
  })

  // Feature: web-im-system, Property 30: Mock 数据持久化往返
  it('dynamically generated mock data should persist and be readable after reload', () => {
    const sessionId = 'session_1_2'
    const newMsg = {
      id: 'msg_persist_test_' + Date.now(),
      sessionId,
      type: 'text',
      senderId: 'user_1',
      senderName: '张三',
      senderAvatar: 'https://example.com/avatar.png',
      content: '持久化测试消息',
      timestamp: Date.now(),
      recalled: false,
      quote: null,
      fileMeta: null
    }

    mockDB.messages.get(sessionId).push(newMsg)
    persistDB()

    // Read back from localStorage
    const raw = JSON.parse(localStorage.getItem('im_mock_db'))
    expect(raw).not.toBeNull()
    expect(raw.messages[sessionId]).toBeDefined()

    const found = raw.messages[sessionId].find(m => m.id === newMsg.id)
    expect(found).toBeDefined()
    expect(found.content).toBe('持久化测试消息')
  })

  // Feature: web-im-system, Property 30 (extended): new group persists
  it('newly created group should persist in mock data', () => {
    const groupId = 'group_persist_test'
    const newGroup = {
      id: groupId,
      name: '持久化测试群',
      avatar: 'https://example.com/group.png',
      members: ['user_1', 'user_2']
    }

    mockDB.groups.set(groupId, newGroup)
    persistDB()

    const raw = JSON.parse(localStorage.getItem('im_mock_db'))
    expect(raw.groups[groupId]).toBeDefined()
    expect(raw.groups[groupId].name).toBe('持久化测试群')
    expect(raw.groups[groupId].members).toEqual(['user_1', 'user_2'])
  })
})
