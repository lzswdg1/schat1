/**
 * 全局通知状态管理
 * 用于 store 驱动的 UI 提示（替代 window.alert）
 */

import { defineStore } from 'pinia'

export const useNotificationStore = defineStore('notification', {
  state: () => ({
    /** @type {{ message: string, type: 'info'|'warning'|'error', id: number }|null} */
    current: null,
    _timer: null
  }),

  actions: {
    show(message, type = 'info', duration = 5000) {
      // 清除上一个定时器
      if (this._timer) {
        clearTimeout(this._timer)
        this._timer = null
      }

      this.current = { message, type, id: Date.now() }

      if (duration > 0) {
        this._timer = setTimeout(() => {
          this.dismiss()
        }, duration)
      }
    },

    dismiss() {
      this.current = null
      if (this._timer) {
        clearTimeout(this._timer)
        this._timer = null
      }
    }
  }
})
