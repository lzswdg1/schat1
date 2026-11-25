/**
 * 聊天 API 属性测试
 * Properties 17, 27
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { uploadFile, getHistory } from '@/api/chat.js'
import { resetDB } from '@/api/mock/data.js'

describe('Chat API Properties', () => {
  beforeEach(() => {
    resetDB()
  })

  // Feature: web-im-system, Property 17: 图片上传返回 URL
  it('uploadFile should return a non-empty url', async () => {
    const mockFile = { name: 'test.png', size: 1024, type: 'image/png' }
    const res = await uploadFile(mockFile)
    expect(res.code).toBe(200)
    expect(res.data.url).toBeTruthy()
    expect(typeof res.data.url).toBe('string')
    expect(res.data.url.length).toBeGreaterThan(0)
    expect(res.data.name).toBe('test.png')
    expect(res.data.size).toBe(1024)
  })

  // Feature: web-im-system, Property 27: 分页历史消息正确性
  it('paginated history should return messages in order with correct cursor/hasMore', async () => {
    // session_1_2 has 4 messages in mock data
    const sessionId = 'session_1_2'

    // First page: get latest 2 messages
    const res1 = await getHistory(sessionId, { size: 2 })
    expect(res1.code).toBe(200)
    expect(res1.data.messages.length).toBe(2)
    expect(res1.data.hasMore).toBe(true)
    expect(res1.data.cursor).toBeTruthy()

    // Messages should be in chronological order (ascending timestamp)
    const msgs1 = res1.data.messages
    for (let i = 1; i < msgs1.length; i++) {
      expect(msgs1[i].timestamp).toBeGreaterThanOrEqual(msgs1[i - 1].timestamp)
    }

    // Second page: use cursor to get older messages
    const res2 = await getHistory(sessionId, { cursor: res1.data.cursor, size: 2 })
    expect(res2.code).toBe(200)
    expect(res2.data.messages.length).toBe(2)
    expect(res2.data.hasMore).toBe(false)
    expect(res2.data.cursor).toBeNull()

    // All messages from page 2 should be older than page 1
    const oldestPage1 = msgs1[0].timestamp
    const newestPage2 = res2.data.messages[res2.data.messages.length - 1].timestamp
    expect(newestPage2).toBeLessThanOrEqual(oldestPage1)
  })

  // Feature: web-im-system, Property 27 (hasMore=false implies null cursor)
  it('when hasMore is false, cursor should be null', async () => {
    // Get all messages at once (large size)
    const res = await getHistory('session_1_3', { size: 100 })
    expect(res.code).toBe(200)
    expect(res.data.hasMore).toBe(false)
    expect(res.data.cursor).toBeNull()
  })
})
