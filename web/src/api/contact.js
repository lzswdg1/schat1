/**
 * 联系人相关 API 接口
 * 纯接口定义，通过 USE_MOCK 路由到 Mock 或 Real 实现
 */

import { USE_MOCK } from './provider.js'
import http from './http.js'

let _mock = null
async function mock() {
  if (!_mock) _mock = await import('./mock/contact.mock.js')
  return _mock
}

/**
 * 好友列表
 * 后端: GET /api/friend/list（token 鉴权）
 */
export async function getFriends(userId) {
  if (USE_MOCK) return (await mock()).getFriends(userId)
  return http.get('/friend/list')
}

/**
 * 搜索用户
 * 后端: GET /api/user/search?accountId=xxx
 */
export async function searchUser(accountId) {
  if (USE_MOCK) return (await mock()).searchUser(accountId)
  return http.get('/user/search', { params: { accountId } })
}

/**
 * 发送好友申请
 * 后端: POST /api/friend/apply body: {targetAccountId, applyReason}
 * @param {string} fromUserId - 当前用户 ID（未使用，由后端 token 鉴权获取）
 * @param {string} targetAccountId - 目标用户的 accountId（用户名）
 */
export async function sendFriendRequest(fromUserId, targetAccountId) {
  if (USE_MOCK) return (await mock()).sendFriendRequest(fromUserId, targetAccountId)
  return http.post('/friend/apply', { targetAccountId, applyReason: '' })
}

/**
 * 获取待处理好友申请
 * 后端: GET /api/friend/my-pending（token 鉴权）
 */
export async function getFriendRequests(userId) {
  if (USE_MOCK) return (await mock()).getFriendRequests(userId)
  return http.get('/friend/my-pending')
}

/**
 * 同意好友申请
 * 后端: POST /api/friend/handle body: {applyId, status: 1}
 */
export async function acceptFriendRequest(userId, requestId) {
  if (USE_MOCK) return (await mock()).acceptFriendRequest(userId, requestId)
  return http.post('/friend/handle', { applyId: requestId, status: 1 })
}

/**
 * 拒绝好友申请
 * 后端: POST /api/friend/handle body: {applyId, status: 2}
 */
export async function rejectFriendRequest(userId, requestId) {
  if (USE_MOCK) return (await mock()).rejectFriendRequest(userId, requestId)
  return http.post('/friend/handle', { applyId: requestId, status: 2 })
}

/**
 * 走当前好友申请流程
 */
export async function addFriend(userId, friendId) {
  if (USE_MOCK) return (await mock()).addFriend(userId, friendId)
  return http.post('/friend/apply', { targetAccountId: friendId, applyReason: '' })
}

/**
 * 删除好友
 * 后端: DELETE /api/friend/delete?friendId=xxx
 */
export async function removeFriend(userId, friendId) {
  if (USE_MOCK) return (await mock()).removeFriend(userId, friendId)
  return http.delete('/friend/delete', { params: { friendId } })
}

/**
 * 群组相关接口（聊天服务）
 */

/**
 * 我的群组列表
 * 后端: GET /api/group/myGroups（token 鉴权）
 */
export async function getGroups(userId) {
  if (USE_MOCK) return (await mock()).getGroups(userId)
  return http.get('/group/myGroups')
}

/**
 * 群成员列表
 * 后端: GET /api/group/members?groupId=xxx
 */
export async function getGroupMembers(groupId) {
  if (USE_MOCK) return (await mock()).getGroupMembers(groupId)
  return http.get('/group/members', { params: { groupId } })
}

/**
 * 创建群聊
 * 后端: POST /api/group/create body: {groupName, memberIds}
 */
export async function createGroup({ name, memberIds, creatorId }) {
  if (USE_MOCK) return (await mock()).createGroup({ name, memberIds, creatorId })
  return http.post('/group/create', { groupName: name, memberIds })
}

export async function leaveGroup(groupId) {
  if (USE_MOCK) {
    const { useAuthStore } = await import('@/stores/auth.js')
    const userId = useAuthStore().user?.id
    return (await mock()).leaveGroup(groupId, userId)
  }
  return http.post('/group/leave', { groupId })
}

export async function kickMember(groupId, userId) {
  if (USE_MOCK) {
    const { useAuthStore } = await import('@/stores/auth.js')
    const operatorId = useAuthStore().user?.id
    return (await mock()).kickMember(groupId, operatorId, userId)
  }
  return http.post('/group/kick', { groupId, userId })
}

export async function transferOwner(groupId, newOwnerId) {
  if (USE_MOCK) {
    const { useAuthStore } = await import('@/stores/auth.js')
    const currentOwnerId = useAuthStore().user?.id
    return (await mock()).transferOwner(groupId, currentOwnerId, newOwnerId)
  }
  return http.post('/group/transferOwner', { groupId, newOwnerId })
}

/** 解散群聊 POST /api/group/dismiss?groupId=xxx */
export async function dismissGroup(groupId) {
  return http.post('/group/dismiss', null, { params: { groupId } })
}

/** 修改群公告 POST /api/group/notice */
export async function updateGroupNotice(groupId, notice) {
  return http.post('/group/notice', { groupId, notice })
}

/** 修改群头像 POST /api/group/avatar */
export async function updateGroupAvatar(groupId, avatar) {
  return http.post('/group/avatar', { groupId, avatar })
}

/** 修改群名称 POST /api/group/rename */
export async function renameGroup(groupId, groupName) {
  return http.post('/group/rename', { groupId, groupName })
}

/** 设置/取消管理员 POST /api/group/setAdmin */
export async function setGroupAdmin(groupId, userId, isAdmin) {
  return http.post('/group/setAdmin', { groupId, userId, isAdmin })
}

/** 邀请成员入群 POST /api/group/invite */
export async function inviteGroupMembers(groupId, memberIds) {
  return http.post('/group/invite', { groupId, memberIds })
}

/** 获取群信息 GET /api/group/info?groupId=xxx */
export async function getGroupInfo(groupId) {
  return http.get('/group/info', { params: { groupId } })
}
