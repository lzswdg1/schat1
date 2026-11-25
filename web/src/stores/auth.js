/**
 * 认证状态管理
 */

import { defineStore } from 'pinia'
import { login as apiLogin, register as apiRegister, logout as apiLogout } from '@/api/auth.js'
import { wsManager } from '@/api/websocket.js'
import { clearAll as clearChatDB } from '@/db/chatDB.js'
import { USE_MOCK } from '@/api/provider.js'
import { normalizeFileUrl } from '@/api/http.js'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    token: null
  }),

  getters: {
    isLoggedIn: (state) => !!state.token
  },

  actions: {
    async login(username, password) {
      const res = await apiLogin(username, password)
      if (res.code === 200) {
        // 安全清理：确保切换账号时不残留旧用户数据
        try {
          const { useSessionStore } = await import('./session.js')
          const { useContactStore } = await import('./contact.js')
          const { useChatStore } = await import('./chat.js')
          useSessionStore().$reset()
          useContactStore().$reset()
          useChatStore().$reset()
          await clearChatDB()
        } catch {
          // 防御性处理
        }

        this.user = USE_MOCK ? res.data.user : {
          id: String(res.data.user.id),
          username: res.data.user.accountId || res.data.user.username,
          nickname: res.data.user.nickname,
          avatar: normalizeFileUrl(res.data.user.avatar)
        }
        this.token = res.data.token
        // 连接 WebSocket
        wsManager.connect(window.location.origin, this.token, this.user.id)
      }
      return res
    },

    async register(username, password, nickname) {
      const res = await apiRegister(username, password, nickname)
      return res
    },

    async logout() {
      // 调后端接口
      await apiLogout(this.token).catch(() => {})
      // 断开 WebSocket
      wsManager.disconnect()
      // 清空本地状态
      this.user = null
      this.token = null
      localStorage.removeItem('session')
      localStorage.removeItem('contact')
      // 清除 IndexedDB
      await clearChatDB()

      // 重置其他 store 的内存状态，防止切换用户时数据串入
      try {
        const { useSessionStore } = await import('./session.js')
        const { useContactStore } = await import('./contact.js')
        const { useChatStore } = await import('./chat.js')
        const sessionStore = useSessionStore()
        const contactStore = useContactStore()
        const chatStore = useChatStore()
        sessionStore.$reset()
        contactStore.$reset()
        chatStore.$reset()
      } catch {
        // 防御性处理
      }
    },

    updateAvatar(avatarUrl) {
      if (this.user) {
        this.user = { ...this.user, avatar: avatarUrl }
      }
    },

    /**
     * 恢复 WebSocket 连接（页面刷新后）
     */
    restoreConnection() {
      if (this.token && this.user?.id) {
        wsManager.connect(window.location.origin, this.token, this.user.id)
      }
    }
  },

  persist: true
})
