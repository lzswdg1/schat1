/**
 * 联系人 API 属性测试
 * Properties 5, 6, 7
 */

import { describe, it, expect, beforeEach } from 'vitest'
import { searchUser, addFriend, removeFriend, getFriends, createGroup, sendFriendRequest, acceptFriendRequest } from '@/api/contact.js'
import { resetDB, mockDB } from '@/api/mock/data.js'

describe('Contact API Properties', () => {
  beforeEach(() => {
    resetDB()
  })

  // Feature: web-im-system, Property 5: 好友搜索返回匹配结果
  it('searching an existing user ID should return correct user info', async () => {
    const userIds = ['user_1', 'user_2', 'user_3', 'user_4', 'user_5', 'user_6']

    for (const userId of userIds) {
      const res = await searchUser(userId)
      expect(res.code).toBe(200)
      expect(res.data).not.toBeNull()
      expect(res.data.id).toBe(userId)
      expect(res.data).toHaveProperty('username')
      expect(res.data).toHaveProperty('nickname')
      expect(res.data).toHaveProperty('avatar')
    }
  }, 15000)

  // Feature: web-im-system, Property 5 (negative): searching non-existent ID returns null
  it('searching a non-existent user ID should return null data', async () => {
    const res = await searchUser('nonexistent_user_xyz')
    expect(res.code).toBe(200)
    expect(res.data).toBeNull()
  })

  // Feature: web-im-system, Property 6: 好友增删往返一致性
  it('adding then removing a friend should restore original friend list', async () => {
    const userId = 'user_1'
    const targetId = 'user_6' // user_6 is not a friend of user_1

    const beforeRes = await getFriends(userId)
    const beforeIds = beforeRes.data.map(f => f.id).sort()

    // 发起好友申请
    const reqRes = await sendFriendRequest(userId, targetId)
    expect(reqRes.code).toBe(200)

    // 对方同意申请
    const acceptRes = await acceptFriendRequest(targetId, reqRes.data.id)
    expect(acceptRes.code).toBe(200)

    // 验证已成为好友
    const midRes = await getFriends(userId)
    expect(midRes.data.map(f => f.id)).toContain(targetId)

    // 删除好友
    const removeRes = await removeFriend(userId, targetId)
    expect(removeRes.code).toBe(200)

    const afterRes = await getFriends(userId)
    const afterIds = afterRes.data.map(f => f.id).sort()
    expect(afterIds).toEqual(beforeIds)
  }, 15000)

  // Feature: web-im-system, Property 7: 群组创建包含指定成员
  it('created group should contain all specified member IDs', async () => {
    const testCases = [
      { name: '测试群1', memberIds: ['user_2', 'user_3'] },
      { name: '测试群2', memberIds: ['user_2', 'user_3', 'user_4', 'user_5'] },
      { name: '测试群3', memberIds: ['user_4'] }
    ]

    for (const { name, memberIds } of testCases) {
      resetDB()
      const creatorId = 'user_1'
      const res = await createGroup({ name, memberIds, creatorId })
      expect(res.code).toBe(200)
      for (const id of memberIds) {
        expect(res.data.members).toContain(id)
      }
      expect(res.data.members).toContain(creatorId)
    }
  }, 15000)
})
