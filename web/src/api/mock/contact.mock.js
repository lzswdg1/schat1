/**
 * 联系人相关 Mock 实现
 */

import { mockDB, persistDB } from './data.js'
import { delay, mockResponse } from './helpers.js'

import { wsManager } from '../websocket.js'
import { installMockBridge } from './websocket.mock.js'

// Ensure mock bridge is installed
if (!wsManager.mockFriendEvent) {
  installMockBridge(wsManager)
}

export async function getFriends(userId) {
  await delay()
  const friendIds = mockDB.friends.get(userId) || []
  const friends = friendIds
    .map(id => mockDB.users.get(id))
    .filter(Boolean)
    .map(u => ({ ...u }))
  return mockResponse(friends)
}

export async function searchUser(userId) {
  await delay()
  const user = mockDB.users.get(userId)
  if (!user) return mockResponse(null)
  return mockResponse({ ...user })
}

export async function sendFriendRequest(fromUserId, toUserId) {
  await delay()

  const toUser = mockDB.users.get(toUserId)
  if (!toUser) return mockResponse(null, 400, '用户不存在')

  const friendIds = mockDB.friends.get(fromUserId) || []
  if (friendIds.includes(toUserId)) {
    return mockResponse(null, 400, '该用户已是您的好友')
  }

  const existingRequests = mockDB.friendRequests.get(toUserId) || []
  if (existingRequests.some(r => r.fromUserId === fromUserId && r.status === 'pending')) {
    return mockResponse(null, 400, '已发送过好友申请，请等待对方处理')
  }

  const fromUser = mockDB.users.get(fromUserId)
  const request = {
    id: `fr_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    fromUserId,
    fromUserName: fromUser?.nickname || fromUserId,
    fromUserAvatar: fromUser?.avatar || '',
    toUserId,
    status: 'pending',
    timestamp: Date.now()
  }

  if (!mockDB.friendRequests.has(toUserId)) {
    mockDB.friendRequests.set(toUserId, [])
  }
  mockDB.friendRequests.get(toUserId).push(request)
  persistDB()

  wsManager.mockFriendEvent('new-friend-request', request)

  return mockResponse(request)
}


export async function getFriendRequests(userId) {
  await delay()
  const requests = (mockDB.friendRequests.get(userId) || [])
    .filter(r => r.status === 'pending')
    .map(r => ({ ...r }))
  return mockResponse(requests)
}

export async function acceptFriendRequest(userId, requestId) {
  await delay()

  const requests = mockDB.friendRequests.get(userId) || []
  const request = requests.find(r => r.id === requestId)
  if (!request) return mockResponse(null, 400, '申请不存在')
  if (request.status !== 'pending') return mockResponse(null, 400, '申请已处理')

  request.status = 'accepted'

  const fromUserId = request.fromUserId
  const toUserId = userId

  if (!mockDB.friends.has(toUserId)) mockDB.friends.set(toUserId, [])
  if (!mockDB.friends.has(fromUserId)) mockDB.friends.set(fromUserId, [])

  const toFriends = mockDB.friends.get(toUserId)
  const fromFriends = mockDB.friends.get(fromUserId)

  if (!toFriends.includes(fromUserId)) toFriends.push(fromUserId)
  if (!fromFriends.includes(toUserId)) fromFriends.push(toUserId)

  const fromUser = mockDB.users.get(fromUserId)
  const toUser = mockDB.users.get(toUserId)
  ensurePrivateSession(toUserId, fromUserId, fromUser)
  ensurePrivateSession(fromUserId, toUserId, toUser)

  persistDB()

  wsManager.mockFriendEvent('friend-accepted', {
    requestId,
    userId: toUserId,
    userName: toUser?.nickname,
    userAvatar: toUser?.avatar
  })

  return mockResponse({ success: true })
}

export async function rejectFriendRequest(userId, requestId) {
  await delay()

  const requests = mockDB.friendRequests.get(userId) || []
  const request = requests.find(r => r.id === requestId)
  if (!request) return mockResponse(null, 400, '申请不存在')
  if (request.status !== 'pending') return mockResponse(null, 400, '申请已处理')

  request.status = 'rejected'
  persistDB()

  return mockResponse({ success: true })
}

export async function addFriend(userId, friendId) {
  return sendFriendRequest(userId, friendId)
}

function ensurePrivateSession(ownerId, targetId, targetUser) {
  if (!targetUser) return
  if (!mockDB.sessions.has(ownerId)) mockDB.sessions.set(ownerId, [])

  const sessions = mockDB.sessions.get(ownerId)
  const exists = sessions.some(s => s.type === 'private' && s.targetId === targetId)

  if (!exists) {
    sessions.push({
      id: `session_${ownerId}_${targetId}_${Date.now()}`,
      type: 'private',
      targetId,
      targetName: targetUser.nickname,
      targetAvatar: targetUser.avatar,
      lastMessage: '',
      lastTime: Date.now(),
      unreadCount: 0
    })
  }
}

export async function removeFriend(userId, friendId) {
  await delay()

  const friendIds = mockDB.friends.get(userId)
  if (friendIds) {
    const idx = friendIds.indexOf(friendId)
    if (idx !== -1) friendIds.splice(idx, 1)
  }

  const otherFriendIds = mockDB.friends.get(friendId)
  if (otherFriendIds) {
    const idx = otherFriendIds.indexOf(userId)
    if (idx !== -1) otherFriendIds.splice(idx, 1)
  }

  removePrivateSession(userId, friendId)
  removePrivateSession(friendId, userId)

  persistDB()

  wsManager.mockFriendEvent('friend-deleted', { userId, friendId })

  return mockResponse({ success: true })
}

function removePrivateSession(ownerId, targetId) {
  const sessions = mockDB.sessions.get(ownerId)
  if (sessions) {
    const idx = sessions.findIndex(s => s.type === 'private' && s.targetId === targetId)
    if (idx !== -1) {
      const removed = sessions.splice(idx, 1)[0]
      mockDB.messages.delete(removed.id)
    }
  }
}

export async function getGroups(userId) {
  await delay()
  const groups = []
  for (const [, group] of mockDB.groups) {
    if (group.members.includes(userId)) {
      groups.push({ ...group, members: [...group.members] })
    }
  }
  return mockResponse(groups)
}

export async function getGroupMembers(groupId) {
  await delay()
  const group = mockDB.groups.get(groupId)
  if (!group) return mockResponse(null, 400, '群组不存在')

  const members = group.members
    .map(id => {
      const user = mockDB.users.get(id)
      if (!user) return null
      return { ...user, role: (group.roles && group.roles[id]) || 'member' }
    })
    .filter(Boolean)

  return mockResponse(members)
}

export async function createGroup({ name, memberIds, creatorId }) {
  await delay()

  const allMembers = memberIds.includes(creatorId)
    ? [...memberIds]
    : [creatorId, ...memberIds]

  const groupId = `group_${Date.now()}`
  const roles = {}
  for (const id of allMembers) {
    roles[id] = id === creatorId ? 'owner' : 'member'
  }

  const newGroup = {
    id: groupId,
    name,
    avatar: `https://api.dicebear.com/7.x/identicon/svg?seed=${groupId}`,
    members: allMembers,
    roles
  }

  mockDB.groups.set(groupId, newGroup)

  if (!mockDB.sessions.has(creatorId)) mockDB.sessions.set(creatorId, [])
  mockDB.sessions.get(creatorId).push({
    id: `session_${groupId}`,
    type: 'group',
    targetId: groupId,
    targetName: name,
    targetAvatar: newGroup.avatar,
    lastMessage: '',
    lastTime: Date.now(),
    unreadCount: 0
  })

  persistDB()
  return mockResponse({ ...newGroup, members: [...allMembers], roles: { ...roles } })
}


export async function leaveGroup(groupId, userId) {
  await delay()

  const group = mockDB.groups.get(groupId)
  if (!group) return mockResponse(null, 400, '群组不存在')

  const role = group.roles?.[userId]
  if (role === 'owner') return mockResponse(null, 400, '群主不能退出群聊，请先转让群主')

  const idx = group.members.indexOf(userId)
  if (idx === -1) return mockResponse(null, 400, '你不在该群中')

  group.members.splice(idx, 1)
  if (group.roles) delete group.roles[userId]

  // 移除该用户的群会话
  const sessions = mockDB.sessions.get(userId)
  if (sessions) {
    const sIdx = sessions.findIndex(s => s.type === 'group' && s.targetId === groupId)
    if (sIdx !== -1) sessions.splice(sIdx, 1)
  }

  persistDB()
  return mockResponse({ success: true })
}

export async function kickMember(groupId, operatorId, targetUserId) {
  await delay()

  const group = mockDB.groups.get(groupId)
  if (!group) return mockResponse(null, 400, '群组不存在')

  const operatorRole = group.roles?.[operatorId]
  if (operatorRole !== 'owner' && operatorRole !== 'admin') {
    return mockResponse(null, 403, '没有权限执行此操作')
  }
  if (operatorId === targetUserId) {
    return mockResponse(null, 400, '不能踢出自己')
  }
  const targetRole = group.roles?.[targetUserId]
  if (targetRole === 'owner') {
    return mockResponse(null, 400, '不能踢出群主')
  }

  const idx = group.members.indexOf(targetUserId)
  if (idx === -1) return mockResponse(null, 400, '该用户不在群中')

  group.members.splice(idx, 1)
  if (group.roles) delete group.roles[targetUserId]

  // 移除被踢用户的群会话
  const sessions = mockDB.sessions.get(targetUserId)
  if (sessions) {
    const sIdx = sessions.findIndex(s => s.type === 'group' && s.targetId === groupId)
    if (sIdx !== -1) sessions.splice(sIdx, 1)
  }

  persistDB()
  return mockResponse({ success: true })
}

export async function transferOwner(groupId, currentOwnerId, newOwnerId) {
  await delay()

  const group = mockDB.groups.get(groupId)
  if (!group) return mockResponse(null, 400, '群组不存在')

  if (group.roles?.[currentOwnerId] !== 'owner') {
    return mockResponse(null, 403, '只有群主可以转让群主')
  }
  if (!group.members.includes(newOwnerId)) {
    return mockResponse(null, 400, '目标用户不在群中')
  }
  if (currentOwnerId === newOwnerId) {
    return mockResponse(null, 400, '不能转让给自己')
  }

  group.roles[currentOwnerId] = 'member'
  group.roles[newOwnerId] = 'owner'

  persistDB()
  return mockResponse({ success: true })
}
