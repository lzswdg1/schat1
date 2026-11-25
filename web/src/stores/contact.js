/**
 * 联系人状态管理
 * 好友列表、群组列表、好友申请、群成员缓存
 */

import { defineStore } from 'pinia'
import { useAuthStore } from './auth.js'
import {
  getFriends,
  searchUser,
  sendFriendRequest,
  getFriendRequests,
  acceptFriendRequest,
  rejectFriendRequest,
  removeFriend,
  getGroups,
  getGroupMembers,
  getGroupInfo,
  createGroup,
  leaveGroup as apiLeaveGroup,
  kickMember as apiKickMember,
  transferOwner as apiTransferOwner,
  dismissGroup as apiDismissGroup,
  updateGroupNotice as apiUpdateGroupNotice,
  renameGroup as apiRenameGroup,
  setGroupAdmin as apiSetGroupAdmin,
  inviteGroupMembers as apiInviteGroupMembers,
  updateGroupAvatar as apiUpdateGroupAvatar
} from '@/api/contact.js'
import { normalizeFileUrl } from '@/api/http.js'

export const useContactStore = defineStore('contact', {
  state: () => ({
    friends: [],
    groups: [],
    searchResult: null,
    friendRequests: [],
    /** @type {Record<string, object[]>} groupId → member list */
    groupMembersCache: {}
  }),

  getters: {
    pendingRequestCount: (state) => state.friendRequests.length
  },

  actions: {
    async fetchFriends() {
      const authStore = useAuthStore()
      const res = await getFriends(authStore.user.id)
      if (res.code === 200) {
        // 后端字段映射：id(Long→String), accountId→username
        this.friends = (res.data || []).map(f => ({
          id: String(f.id),
          username: f.accountId || f.username,
          nickname: f.nickname,
          avatar: normalizeFileUrl(f.avatar)
        }))
      }
      return res
    },

    async searchUser(userId) {
      const res = await searchUser(userId)
      if (res.code === 200 && res.data) {
        // 后端字段映射：id(Long→String), accountId→username
        this.searchResult = {
          id: String(res.data.id),
          username: res.data.accountId,
          nickname: res.data.nickname,
          avatar: normalizeFileUrl(res.data.avatar)
        }
      } else {
        this.searchResult = null
      }
      return res
    },

    async sendFriendRequest(userId) {
      const authStore = useAuthStore()
      const res = await sendFriendRequest(authStore.user.id, userId)
      return res
    },

    async fetchFriendRequests() {
      const authStore = useAuthStore()
      const res = await getFriendRequests(authStore.user.id)
      if (res.code === 200) {
        // PendingRequestVO 字段映射
        this.friendRequests = (res.data || []).map(r => ({
          id: r.id,
          fromUserId: String(r.fromUserId || ''),
          fromUserName: r.fromUserName || '',
          fromUserAvatar: normalizeFileUrl(r.fromUserAvatar || ''),
          // createTime 是 ISO date-time 字符串，转为时间戳
          timestamp: r.createTime ? new Date(r.createTime).getTime() : (r.timestamp || 0),
          applicantAccountId: r.applicantAccountId || ''
        }))
      }
      return res
    },

    async acceptRequest(requestId) {
      const authStore = useAuthStore()
      const res = await acceptFriendRequest(authStore.user.id, requestId)
      if (res.code === 200) {
        await this.fetchFriendRequests()
        await this.fetchFriends()
      }
      return res
    },

    async rejectRequest(requestId) {
      const authStore = useAuthStore()
      const res = await rejectFriendRequest(authStore.user.id, requestId)
      if (res.code === 200) {
        await this.fetchFriendRequests()
      }
      return res
    },

    /**
     * 兼容旧的 addFriend（现在走申请流程）
     */
    async addFriend(userId) {
      return this.sendFriendRequest(userId)
    },

    async removeFriend(friendId) {
      const authStore = useAuthStore()
      const res = await removeFriend(authStore.user.id, friendId)
      if (res.code === 200) {
        this.friends = this.friends.filter(f => f.id !== friendId)
      }
      return res
    },

    async fetchGroups() {
      const authStore = useAuthStore()
      const res = await getGroups(authStore.user.id)
      if (res.code === 200) {
        this.groups = (res.data || []).map(g => ({
          ...g,
          avatar: normalizeFileUrl(g.avatar)
        }))
      }
      return res
    },

    /**
     * 获取群成员列表（带缓存）
     */
    async fetchGroupMembers(groupId) {
      if (this.groupMembersCache[groupId]) {
        return { code: 200, data: this.groupMembersCache[groupId] }
      }
      const res = await getGroupMembers(groupId)
      if (res.code === 200) {
        this.groupMembersCache[groupId] = res.data
      }
      return res
    },

    async createGroup({ name, memberIds }) {
      const authStore = useAuthStore()
      const res = await createGroup({ name, memberIds, creatorId: authStore.user.id })
      if (res.code === 200) this.groups.push(res.data)
      return res
    },

    /**
     * 退出群聊
     * 成功后从本地群列表移除，清除群成员缓存
     */
    async leaveGroup(groupId) {
      const res = await apiLeaveGroup(groupId)
      if (res.code === 200) {
        const gid = String(groupId)
        this.groups = this.groups.filter(g => String(g.groupId || g.id) !== gid)
        delete this.groupMembersCache[groupId]
      }
      return res
    },

    /**
     * 踢出群成员
     * 成功后刷新群成员缓存
     */
    async kickMember(groupId, userId) {
      const res = await apiKickMember(groupId, userId)
      if (res.code === 200) {
        delete this.groupMembersCache[groupId]
        await this.fetchGroupMembers(groupId)
      }
      return res
    },

    /**
     * 转让群主
     * 成功后刷新群成员缓存
     */
    async transferOwner(groupId, newOwnerId) {
      const res = await apiTransferOwner(groupId, newOwnerId)
      if (res.code === 200) {
        delete this.groupMembersCache[groupId]
        await this.fetchGroupMembers(groupId)
      }
      return res
    },

    /** 解散群聊（仅群主） */
    async dismissGroup(groupId) {
      const res = await apiDismissGroup(groupId)
      if (res.code === 200) {
        const gid = String(groupId)
        this.groups = this.groups.filter(g => String(g.groupId || g.id) !== gid)
        delete this.groupMembersCache[groupId]
      }
      return res
    },

    /** 修改群公告 */
    async updateGroupNotice(groupId, notice) {
      const res = await apiUpdateGroupNotice(groupId, notice)
      return res
    },

    /** 修改群名称 */
    async renameGroup(groupId, groupName) {
      const res = await apiRenameGroup(groupId, groupName)
      if (res.code === 200) {
        const group = this.groups.find(g => String(g.groupId || g.id) === String(groupId))
        if (group) group.groupName = groupName
      }
      return res
    },

    /** 设置/取消管理员 */
    async setGroupAdmin(groupId, userId, isAdmin) {
      const res = await apiSetGroupAdmin(groupId, userId, isAdmin)
      if (res.code === 200) {
        delete this.groupMembersCache[groupId]
        await this.fetchGroupMembers(groupId)
      }
      return res
    },

    /** 邀请成员入群 */
    async inviteGroupMembers(groupId, memberIds) {
      const res = await apiInviteGroupMembers(groupId, memberIds)
      if (res.code === 200) {
        delete this.groupMembersCache[groupId]
        await this.fetchGroupMembers(groupId)
      }
      return res
    },

    /** 修改群头像 */
    async updateGroupAvatar(groupId, avatar) {
      const res = await apiUpdateGroupAvatar(groupId, avatar)
      if (res.code === 200) {
        const group = this.groups.find(g => String(g.groupId || g.id) === String(groupId))
        if (group) group.avatar = avatar
      }
      return res
    },

    /** 获取群信息 */
    async fetchGroupInfo(groupId) {
      const res = await getGroupInfo(groupId)
      return res
    },

    /**
     * WebSocket 事件：收到新好友申请
     */
    handleNewFriendRequest(request) {
      if (!this.friendRequests.some(r => r.id === request.id)) {
        this.friendRequests.push(request)
      }
    },

    /**
     * WebSocket 事件：对方同意了我的申请
     */
    async handleFriendAccepted() {
      await this.fetchFriends()
    },

    /**
     * WebSocket 事件：被对方删除
     */
    handleFriendDeleted(data) {
      this.friends = this.friends.filter(f => f.id !== data.userId)
    },

    /** WebSocket 事件：群被解散 */
    handleGroupDismissed(data) {
      const gid = String(data.groupId)
      this.groups = this.groups.filter(g => String(g.groupId || g.id) !== gid)
      delete this.groupMembersCache[gid]
    },

    /** WebSocket 事件：被踢出群 */
    handleGroupKicked(data) {
      const gid = String(data.groupId)
      this.groups = this.groups.filter(g => String(g.groupId || g.id) !== gid)
      delete this.groupMembersCache[gid]
    },

    /** WebSocket 事件：有成员退出群 */
    handleGroupMemberLeft(data) {
      const gid = String(data.groupId)
      delete this.groupMembersCache[gid]
    }
  },

  persist: true
})
