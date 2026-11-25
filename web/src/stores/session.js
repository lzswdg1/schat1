/**
 * 会话状态管理
 * 管理会话列表、活跃会话、分类折叠状态和未读计数
 */

import { defineStore } from 'pinia'
import { useAuthStore } from './auth.js'
import { USE_MOCK } from '@/api/provider.js'

export const useSessionStore = defineStore('session', {
  state: () => ({
    sessions: [],
    activeSessionId: null,
    collapsedGroups: {
      private: false,
      group: false
    }
  }),

  getters: {
    /** 优化原因：getter 直接返回已排序数组，不再每次访问都 sort() */
    privateSessions: (state) => state.sessions.filter(s => s.type === 'private'),
    groupSessions: (state) => state.sessions.filter(s => s.type === 'group'),
    activeSession: (state) => state.sessions.find(s => s.id === state.activeSessionId),
    privateUnreadCount: (state) =>
      state.sessions
        .filter(s => s.type === 'private')
        .reduce((sum, s) => sum + s.unreadCount, 0),
    groupUnreadCount: (state) =>
      state.sessions
        .filter(s => s.type === 'group')
        .reduce((sum, s) => sum + s.unreadCount, 0)
  },

  actions: {
    setActiveSession(sessionId) {
      this.activeSessionId = sessionId
    },

    toggleCollapse(groupType) {
      this.collapsedGroups[groupType] = !this.collapsedGroups[groupType]
    },

    /**
     * 优化原因：更新后将该会话移到数组头部（按 lastTime 降序），
     * 避免 getter 每次访问都重新 sort()
     */
    updateLastMessage(sessionId, message) {
      const idx = this.sessions.findIndex(s => s.id === sessionId)
      if (idx !== -1) {
        const session = this.sessions[idx]
        session.lastMessage = message.recalled ? '[消息已撤回]' : message.content
        session.lastMessageId = message.id == null ? null : String(message.id)
        session.lastTime = message.timestamp
        // 移到数组头部维护有序性
        if (idx > 0) {
          this.sessions.splice(idx, 1)
          this.sessions.unshift(session)
        }
      }
    },

    replacePreviewMessageId(sessionId, oldId, newId) {
      const session = this.sessions.find(item => item.id === sessionId)
      if (session?.lastMessageId != null && String(session.lastMessageId) === String(oldId)) {
        session.lastMessageId = String(newId)
      }
    },

    applyMessageRecall(sessionId, messageId) {
      const session = this.sessions.find(item => item.id === sessionId)
      if (session?.lastMessageId != null && String(session.lastMessageId) === String(messageId)) {
        session.lastMessage = '[消息已撤回]'
      }
    },

    incrementUnread(sessionId) {
      const session = this.sessions.find(s => s.id === sessionId)
      if (session) {
        session.unreadCount++
      }
    },

    clearUnread(sessionId) {
      const session = this.sessions.find(s => s.id === sessionId)
      if (session) {
        session.unreadCount = 0
      }
    },

    async loadSessions() {
      if (!USE_MOCK) {
        // 真实模式：从后端拉取会话列表（token 鉴权，不传 userId）
        try {
          const { default: http, normalizeFileUrl } = await import('@/api/http.js')
          const res = await http.get('/chat/sessions')
          if (res.code === 200 && Array.isArray(res.data)) {
            const serverSessions = res.data.map(s => ({
              id: String(s.id),
              type: s.type || 'private',
              targetId: String(s.targetId),
              targetName: s.targetName || '',
              targetAvatar: normalizeFileUrl(s.targetAvatar),
              lastMessage: s.lastMessage || '',
              lastMessageId: s.lastMessageId == null ? null : String(s.lastMessageId),
              lastTime: s.lastTime || 0,
              unreadCount: s.unreadCount || 0
            }))

            // 合并策略：后端数据为基础，保留本地已有但后端没返回的会话（刚收到的新消息创建的）
            const serverIds = new Set(serverSessions.map(s => s.id))
            const localOnly = this.sessions.filter(s => !serverIds.has(s.id))

            // 后端会话更新本地未读数（取本地和后端的较大值，防止覆盖实时推送增加的未读）
            for (const ss of serverSessions) {
              const local = this.sessions.find(s => s.id === ss.id)
              if (local && local.unreadCount > ss.unreadCount) {
                ss.unreadCount = local.unreadCount
              }
            }

            this.sessions = [...serverSessions, ...localOnly]
              .sort((a, b) => (b.lastTime || 0) - (a.lastTime || 0))
          }
        } catch {
          // 接口不可用时保留 persist 恢复的数据
        }
        return
      }
      // Mock 模式：从 mockDB 加载
      const { mockDB } = await import('@/api/mock/data.js')
      const authStore = useAuthStore()
      if (authStore.user) {
        const userSessions = mockDB.sessions.get(authStore.user.id)
        if (userSessions) {
          this.sessions = userSessions
            .map(s => ({ ...s }))
            .sort((a, b) => (b.lastTime || 0) - (a.lastTime || 0))
        }
      }
    },

    /**
     * 删除与某用户的私聊会话
     */
    removeSessionByTarget(type, targetId) {
      const tid = String(targetId)
      const idx = this.sessions.findIndex(s => s.type === type && String(s.targetId) === tid)
      if (idx !== -1) {
        const removed = this.sessions.splice(idx, 1)[0]
        if (this.activeSessionId === removed.id) {
          this.activeSessionId = null
        }
        return removed.id
      }
      return null
    },

    openSession(type, target) {
      const targetId = String(target.id)
      let session = this.sessions.find(
        s => s.type === type && String(s.targetId) === targetId
      )
      if (!session) {
        // 真实模式：id 与后端一致（私聊用对方 userId，群聊用 group_id）
        const sessionId = USE_MOCK
          ? `session_${Date.now()}_${targetId}`
          : (type === 'group' ? `group_${targetId}` : targetId)
        session = {
          id: sessionId,
          type,
          targetId,
          targetName: target.nickname || target.name,
          targetAvatar: target.avatar || '',
          lastMessage: '',
          lastTime: Date.now(),
          unreadCount: 0
        }
        this.sessions.push(session)
        // Mock 模式：同步到 mockDB
        if (USE_MOCK) {
          import('@/api/mock/data.js').then(({ mockDB }) => {
            const authStore = useAuthStore()
            if (authStore.user) {
              const userId = authStore.user.id
              if (!mockDB.sessions.has(userId)) {
                mockDB.sessions.set(userId, [])
              }
              mockDB.sessions.get(userId).push({ ...session })
            }
          })
        }
      }
      this.activeSessionId = session.id
      this.clearUnread(session.id)
    }
  },

  persist: true
})
