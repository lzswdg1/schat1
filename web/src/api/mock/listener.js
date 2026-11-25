/**
 * MessageListener — EventBus 推送模拟
 * 基于 EventEmitter 模式模拟 WebSocket 实时推送机制
 * 单例导出，全局共享同一实例
 */

import { mockDB } from './data.js'
import { MessageType } from '@/constants/messageType.js'

// 预定义的自动回复内容
const AUTO_REPLY_PHRASES = [
  '好的，我知道了',
  '收到，稍后回复你',
  '没问题，马上处理',
  '这个方案不错，我同意',
  '我正在忙，等会儿聊',
  '哈哈，有意思',
  '明天再说吧',
  '你说得对',
  '我看看，稍等一下',
  '了解了，谢谢',
  '好的好的',
  '嗯嗯，继续说',
  '这个我也觉得可以',
  '行，就这么定了',
  '等我一下，马上来'
]

class MessageListener {
  constructor() {
    /** @type {Map<string, Set<Function>>} */
    this._events = new Map()
    this._autoReplyTimer = null
    this._autoReplySessionId = null
  }

  /**
   * 注册事件监听
   * @param {string} event - 事件名称
   * @param {Function} callback - 回调函数
   */
  on(event, callback) {
    if (!this._events.has(event)) {
      this._events.set(event, new Set())
    }
    this._events.get(event).add(callback)
  }

  /**
   * 移除事件监听
   * @param {string} event - 事件名称
   * @param {Function} callback - 回调函数
   */
  off(event, callback) {
    const callbacks = this._events.get(event)
    if (callbacks) {
      callbacks.delete(callback)
    }
  }

  /**
   * 触发事件
   * @param {string} event - 事件名称
   * @param {*} data - 事件数据
   */
  emit(event, data) {
    const callbacks = this._events.get(event)
    if (callbacks) {
      callbacks.forEach(cb => cb(data))
    }
  }

  /**
   * 启动自动回复定时推送
   * 每隔 10s-30s 随机间隔，推送一条模拟的对方新消息
   * @param {string} sessionId - 会话 ID
   */
  startAutoReply(sessionId) {
    // 先停止之前的自动回复
    this.stopAutoReply()
    this._autoReplySessionId = sessionId

    const scheduleNext = () => {
      const interval = 10000 + Math.random() * 20000 // 10s-30s
      this._autoReplyTimer = setTimeout(() => {
        this._pushAutoReply(sessionId)
        scheduleNext()
      }, interval)
    }

    scheduleNext()
  }

  /**
   * 停止自动回复定时推送
   */
  stopAutoReply() {
    if (this._autoReplyTimer) {
      clearTimeout(this._autoReplyTimer)
      this._autoReplyTimer = null
    }
    this._autoReplySessionId = null
  }

  /**
   * 生成并推送一条模拟的对方消息
   * @param {string} sessionId - 会话 ID
   * @private
   */
  _pushAutoReply(sessionId) {
    // 查找会话信息，确定对方用户
    const sender = this._findOtherUser(sessionId)
    if (!sender) return

    const content = AUTO_REPLY_PHRASES[Math.floor(Math.random() * AUTO_REPLY_PHRASES.length)]

    const message = {
      id: `msg_auto_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
      sessionId,
      type: MessageType.TEXT,
      senderId: sender.id,
      senderName: sender.nickname,
      senderAvatar: sender.avatar,
      content,
      timestamp: Date.now(),
      recalled: false,
      quote: null,
      fileMeta: null
    }

    this.emit('new-message', message)
  }

  /**
   * 从会话中找到对方用户
   * 遍历所有用户的会话列表，找到匹配的会话并返回对方用户信息
   * @param {string} sessionId - 会话 ID
   * @returns {object|null} 对方用户对象
   * @private
   */
  _findOtherUser(sessionId) {
    // 查找当前登录用户（通过 token 映射）
    let currentUserId = null
    for (const [, uid] of mockDB.tokens) {
      currentUserId = uid
      break
    }

    // 在用户的会话列表中查找目标会话
    if (currentUserId) {
      const sessions = mockDB.sessions.get(currentUserId) || []
      const session = sessions.find(s => s.id === sessionId)
      if (session) {
        if (session.type === 'private') {
          // 私聊：targetId 就是对方用户
          return mockDB.users.get(session.targetId) || null
        } else {
          // 群聊：从群组成员中随机选一个非当前用户
          const group = mockDB.groups.get(session.targetId)
          if (group) {
            const otherMembers = group.members.filter(id => id !== currentUserId)
            if (otherMembers.length > 0) {
              const randomId = otherMembers[Math.floor(Math.random() * otherMembers.length)]
              return mockDB.users.get(randomId) || null
            }
          }
        }
      }
    }

    return null
  }
}

// 单例导出
export const messageListener = new MessageListener()
