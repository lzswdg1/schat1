/**
 * 聊天状态管理
 * 优化原因：消息持久化从 localStorage 迁移到 IndexedDB，
 * Pinia 内存中只保留当前活跃会话的消息，历史消息按需从 IndexedDB 加载。
 * 避免 localStorage 5-10MB 限制和大对象序列化性能瓶颈。
 */

import { defineStore } from 'pinia'
import { useAuthStore } from './auth.js'
import { useContactStore } from './contact.js'
import { useSessionStore } from './session.js'
import {
  getHistory,
  sendMessage as apiSendMessage,
  recallMessage as apiRecallMessage,
  sendReadReceipt as apiSendReadReceipt,
  markGroupRead as apiMarkGroupRead,
  syncMessages as apiSyncMessages,
  syncGroupMessages as apiSyncGroupMessages
} from '@/api/chat.js'
import { USE_MOCK } from '@/api/provider.js'
import { normalizeFileUrl } from '@/api/http.js'
import { wsManager } from '@/api/websocket.js'
import { SendStatus, DeliveryStatus } from '@/constants/messageType.js'
import * as chatDB from '@/db/chatDB.js'

const MAX_CACHE_SIZE = 500

/**
 * 将后端时间字段转为毫秒时间戳
 * 支持：毫秒数、秒数、ISO 日期字符串
 */
function _parseTime(val) {
  if (!val) return 0
  if (typeof val === 'number') return val > 1e12 ? val : val * 1000
  if (typeof val === 'string') {
    const ms = new Date(val).getTime()
    return isNaN(ms) ? 0 : ms
  }
  return 0
}

/**
 * 标准化后端消息字段，确保 id/sessionId 等关键字段存在且类型正确
 * 防止 IndexedDB 联合主键 [sessionId+id] 因 undefined 报 DataError
 */
function normalizeMessage(msg, sessionId) {
  if (USE_MOCK) return { ...msg, sessionId: msg.sessionId || sessionId }

  // id 是雪花 ID (Long)，超过 JS 安全整数范围，转字符串
  const id = String(msg.id || msg.messageId || '')
  const type = (msg.type || msg.contentType || 'text').toLowerCase()

  // 图片消息：content 是 JSON 字符串 {"url":"/api/file/xxx.png","w":1176,"h":788}
  // 需要提取 url 作为 content，原始 JSON 保留到 fileMeta
  let content = msg.content || msg.cipherContent || msg.body || msg.text || msg.briefContent || ''
  let fileMeta = msg.fileMeta || null
  if (type === 'image' && content.startsWith('{')) {
    try {
      const parsed = JSON.parse(content)
      content = normalizeFileUrl(parsed.url || '')
      fileMeta = { width: parsed.w, height: parsed.h, ...fileMeta }
    } catch {
      // 解析失败保持原样
    }
  }
  // 图片消息：确保 URL 有 /api 前缀（不管是 JSON 解析后还是纯路径）
  if (type === 'image') {
    content = normalizeFileUrl(content)
  }

  // 根据 senderId 查找发送者信息（后端不返回 senderName/senderAvatar）
  const senderId = String(msg.senderId || msg.fromUserId || '')
  let senderName = msg.senderName || msg.fromUserName || msg.fromNickname || ''
  let senderAvatar = msg.senderAvatar || msg.fromUserAvatar || ''

  if (!senderName) {
    const authStore = useAuthStore()
    if (senderId === String(authStore.user?.id)) {
      senderName = authStore.user?.nickname || ''
      senderAvatar = senderAvatar || authStore.user?.avatar || ''
    } else {
      try {
        const contactStore = useContactStore()
        // 先从好友列表查
        const friend = contactStore.friends.find(f => String(f.id) === senderId)
        if (friend) {
          senderName = friend.nickname || ''
          senderAvatar = senderAvatar || friend.avatar || ''
        } else {
          // 好友列表没有，从群成员缓存查（群成员不一定是好友）
          const sid = msg.sessionId || sessionId
          if (sid && sid.startsWith('group_')) {
            const groupId = sid.replace('group_', '')
            const members = contactStore.groupMembersCache[groupId]
            if (members) {
              const member = members.find(m => String(m.userId || m.id) === senderId)
              if (member) {
                senderName = member.nickname || String(member.userId || member.id)
                senderAvatar = senderAvatar || member.avatar || ''
              }
            }
          }
        }
      } catch {
        // store 未初始化
      }
    }
  }

  const serverDeliveryStatus = typeof msg.deliveryStatus === 'number'
    ? ({ 0: DeliveryStatus.SENT, 1: DeliveryStatus.DELIVERED, 2: DeliveryStatus.READ }[msg.deliveryStatus])
    : msg.deliveryStatus

  return {
    id,
    seq: msg.seq == null ? null : String(msg.seq),
    deliveryRevision: Number(msg.deliveryRevision || 0),
    clientMsgId: msg.clientMsgId || null,
    sessionId: msg.sessionId || sessionId,
    type,
    senderId,
    senderName,
    senderAvatar: normalizeFileUrl(senderAvatar),
    content,
    timestamp: msg.timestamp || _parseTime(msg.createTime) || _parseTime(msg.sendTime) || 0,
    recalled: !!msg.recalled,
    quote: msg.quote || null,
    fileMeta,
    sendStatus: msg.sendStatus || SendStatus.SUCCESS,
    deliveryStatus: serverDeliveryStatus || (msg.sendStatus === SendStatus.SUCCESS ? DeliveryStatus.SENT : null)
  }
}

export const useChatStore = defineStore('chat', {
  state: () => ({
    /** 内存缓存：只保留当前活跃会话的消息 */
    messages: {},
    quoteMessage: null,
    hasMore: {},
    cursors: {},
    /** 优化原因：O(1) 去重查找，替代 Array.some() 的 O(n) 遍历 */
    _idSets: {}
  }),

  actions: {
    /**
     * 初始化去重 Set（会话首次加载时调用）
     */
    _ensureIdSet(sessionId) {
      if (!this._idSets[sessionId]) {
        this._idSets[sessionId] = { ids: new Set(), clientIds: new Set() }
      }
      return this._idSets[sessionId]
    },

    /**
     * 将消息注册到去重 Set
     */
    _trackMessage(sessionId, msg) {
      const set = this._ensureIdSet(sessionId)
      if (msg.id) set.ids.add(msg.id)
      if (msg.clientMsgId) set.clientIds.add(msg.clientMsgId)
    },

    /**
     * 从 IndexedDB 加载会话消息到内存
     */
    async loadFromDB(sessionId) {
      if (this.messages[sessionId]?.length) return

      const raw = await chatDB.getRecentMessages(sessionId, MAX_CACHE_SIZE)
      // 过滤掉脏数据（之前 bug 写入的空 content 消息）
      const valid = raw.filter(m => m.id && (m.content || m.recalled))

      // 基于 clientMsgId 去重：同一个 clientMsgId 可能存在 pending 和 confirmed 两条记录
      // 优先保留 confirmed（id 不以 cmsg_ 开头的）
      const byClientId = new Map()
      const noClientId = []
      for (const m of valid) {
        if (m.clientMsgId) {
          const existing = byClientId.get(m.clientMsgId)
          if (!existing) {
            byClientId.set(m.clientMsgId, m)
          } else {
            // 保留 id 不是 clientMsgId 的那条（即服务端确认后的记录）
            const existingIsPending = existing.id === existing.clientMsgId || existing.id?.startsWith('cmsg_')
            const currentIsPending = m.id === m.clientMsgId || m.id?.startsWith('cmsg_')
            if (existingIsPending && !currentIsPending) {
              byClientId.set(m.clientMsgId, m)
              // 清理 IndexedDB 中的旧 pending 记录
              chatDB.deleteMessage(sessionId, existing.id).catch(() => {})
            } else if (!existingIsPending && currentIsPending) {
              // 清理当前的 pending 记录
              chatDB.deleteMessage(sessionId, m.id).catch(() => {})
            }
          }
        } else {
          noClientId.push(m)
        }
      }
      const msgs = [...byClientId.values(), ...noClientId]
        .sort((a, b) => (a.timestamp || 0) - (b.timestamp || 0))

      this.messages[sessionId] = msgs

      // 重建去重 Set
      const set = this._ensureIdSet(sessionId)
      for (const m of msgs) {
        if (m.recalled) this.applyRecallProjection(sessionId, m)
        if (m.id) set.ids.add(m.id)
        if (m.clientMsgId) set.clientIds.add(m.clientMsgId)
      }

      // 恢复游标
      const cursorInfo = await chatDB.getCursor(sessionId)
      if (cursorInfo) {
        this.hasMore[sessionId] = cursorInfo.hasMore
        this.cursors[sessionId] = cursorInfo.cursor
      }
    },

    /**
     * 切换活跃会话时，释放非活跃会话的内存缓存
     */
    evictSession(sessionId) {
      delete this.messages[sessionId]
      delete this._idSets[sessionId]
      // 保留 hasMore/cursors 元信息，避免重复请求
    },

    async fetchHistory(sessionId, cursor) {
      const res = await getHistory(sessionId, { cursor })
      if (res.code === 200) {
        const { messages: rawMsgs, cursor: nextCursor, hasMore } = res.data
        const messages = await chatDB.putMessages(rawMsgs.map(m => normalizeMessage(m, sessionId)))
        if (!this.messages[sessionId]) {
          this.messages[sessionId] = []
        }

        const set = this._ensureIdSet(sessionId)
        for (const message of messages) {
          if (message.recalled) this.applyRecallProjection(sessionId, message)
          if (set.ids.has(message.id)) {
            const existing = this.messages[sessionId].find(item => item.id === message.id)
            if (existing) Object.assign(existing, message)
          }
        }
        const newMsgs = messages.filter(m =>
          !set.ids.has(m.id) && (!m.clientMsgId || !set.clientIds.has(m.clientMsgId))
        )

        // prepend 到内存
        this.messages[sessionId] = [...newMsgs, ...this.messages[sessionId]]
        for (const m of newMsgs) this._trackMessage(sessionId, m)
        this._enforceLimit(sessionId)

        this.hasMore[sessionId] = hasMore
        this.cursors[sessionId] = nextCursor

        // 写入 IndexedDB
        await chatDB.saveCursor(sessionId, nextCursor, hasMore)
      }
      return res
    },

    async sendMessage(sessionId, { type, content, quoteId, fileMeta }) {
      const authStore = useAuthStore()
      const clientMsgId = `cmsg_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

      const quote = quoteId && this.quoteMessage
        ? {
            messageId: this.quoteMessage.id,
            senderName: this.quoteMessage.senderName,
            content: this.quoteMessage.content
          }
        : null

      const pendingMsg = {
        id: clientMsgId,
        clientMsgId,
        sessionId,
        type,
        senderId: authStore.user?.id,
        senderName: authStore.user?.nickname,
        senderAvatar: authStore.user?.avatar,
        content,
        timestamp: Date.now(),
        recalled: false,
        quote,
        fileMeta: fileMeta || null,
        sendStatus: SendStatus.SENDING,
        deliveryStatus: null
      }

      if (!this.messages[sessionId]) {
        this.messages[sessionId] = []
      }
      this.messages[sessionId].push(pendingMsg)
      this._trackMessage(sessionId, pendingMsg)
      this._enforceLimit(sessionId)

      if (quote) this.clearQuote()

      // 写入 IndexedDB（sending 状态）
      await chatDB.putMessage(pendingMsg)

      const wsPayload = {
        clientMsgId,
        sessionId,
        type,
        content,
        senderId: authStore.user?.id,
        senderName: authStore.user?.nickname,
        senderAvatar: authStore.user?.avatar,
        quote,
        fileMeta: fileMeta || null
      }

      if (USE_MOCK) {
        // Mock 模式：通过 HTTP mock 触发 mockDB 写入 + mockAck
        const res = await apiSendMessage(sessionId, wsPayload)
        if (res.code !== 200) {
          const msg = this.messages[sessionId]?.find(m => m.clientMsgId === clientMsgId)
          if (msg) {
            msg.sendStatus = SendStatus.FAIL
            await chatDB.updateMessage(sessionId, msg.id, { sendStatus: SendStatus.FAIL })
          }
        }
        // Mock ACK 会通过 wsManager.mockAck → handleMsgAck 处理确认
        return res
      } else {
        // 真实模式：优先 WebSocket，连接不可用时回退 HTTP
        if (wsManager.isConnected) {
          wsManager.send('SEND_MESSAGE', wsPayload)
        } else {
          // WebSocket 未连接，回退到 HTTP 发送
          try {
            const res = await apiSendMessage(sessionId, wsPayload)
            if (res.code === 200) {
              // HTTP 发送成功，手动确认消息
              const serverMsg = res.data
              const msgObj = this.messages[sessionId]?.find(m => m.clientMsgId === clientMsgId)
              if (msgObj) {
                msgObj.id = String(serverMsg.messageId || serverMsg.id || msgObj.id)
                msgObj.seq = serverMsg.seq == null ? msgObj.seq : String(serverMsg.seq)
                msgObj.sendStatus = SendStatus.SUCCESS
                msgObj.deliveryStatus = DeliveryStatus.SENT
                Object.assign(msgObj, await chatDB.putMessage(msgObj))
              }
            } else {
              const msgObj = this.messages[sessionId]?.find(m => m.clientMsgId === clientMsgId)
              if (msgObj) {
                msgObj.sendStatus = SendStatus.FAIL
                await chatDB.updateMessage(sessionId, msgObj.id, { sendStatus: SendStatus.FAIL })
              }
            }
            return res
          } catch {
            const msgObj = this.messages[sessionId]?.find(m => m.clientMsgId === clientMsgId)
            if (msgObj) {
              msgObj.sendStatus = SendStatus.FAIL
              await chatDB.updateMessage(sessionId, msgObj.id, { sendStatus: SendStatus.FAIL })
            }
          }
        }
      }
    },

    async recallMessage(sessionId, messageId) {
      const res = await apiRecallMessage(sessionId, messageId)
      if (res.code === 200) {
        const recalled = await chatDB.updateMessage(sessionId, messageId, { recalled: true })
        this.applyRecallProjection(sessionId, recalled || { id: messageId, recalled: true })
        const msgs = this.messages[sessionId]
        if (msgs) {
          const msg = msgs.find(m => m.id === messageId)
          if (msg) Object.assign(msg, recalled || { recalled: true, content: '', quote: null, fileMeta: null })
        }
      }
      return res
    },

    applyRecallProjection(sessionId, message) {
      if (!message.recalled) return
      useSessionStore().applyMessageRecall(sessionId, message.id)
      if (this.quoteMessage && String(this.quoteMessage.id) === String(message.id)) this.quoteMessage = null
    },

    /**
     * 追加消息（带 O(1) Set 去重）
     * 优化原因：替代 Array.some() 的 O(n) 遍历
     */
    async appendMessage(sessionId, message) {
      const normalized = normalizeMessage(message, sessionId)
      const { inserted, message: msg } = await chatDB.putReceivedMessage(normalized)
      if (msg.recalled) this.applyRecallProjection(sessionId, msg)
      console.log('[CHAT] appendMessage:', sessionId, 'msgId:', msg.id, 'content:', msg.content?.slice(0, 20))
      if (!this.messages[sessionId]) {
        this.messages[sessionId] = []
        this._ensureIdSet(sessionId)
      }

      const set = this._ensureIdSet(sessionId)
      if (msg.id && set.ids.has(msg.id)) {
        const existing = this.messages[sessionId].find(message => message.id === msg.id)
        if (existing) Object.assign(existing, msg)
        return false
      }
      if (msg.clientMsgId && set.clientIds.has(msg.clientMsgId)) return false

      this.messages[sessionId].push(msg)
      this._trackMessage(sessionId, msg)
      this._enforceLimit(sessionId)

      return inserted
    },

    /**
     * 更新消息送达状态
     */
    async updateDeliveryStatus(messageIds, status) {
      const idSet = new Set(messageIds)
      for (const [, msgs] of Object.entries(this.messages)) {
        for (const msg of msgs) {
          if (idSet.has(msg.id)) {
            if (msg.deliveryStatus !== DeliveryStatus.READ || status === DeliveryStatus.READ) {
              msg.deliveryStatus = status
            }
          }
        }
      }
      // 同步到 IndexedDB
      await chatDB.updateDeliveryStatus(messageIds, status)
    },

    async sendReadReceipt(sessionId) {
      const authStore = useAuthStore()
      const msgs = this.messages[sessionId] || []
      const unreadIds = msgs
        .filter(m => m.id && String(m.senderId) !== String(authStore.user?.id) && m.deliveryStatus !== DeliveryStatus.READ && !m.id.startsWith('cmsg_'))
        .map(m => m.id)

      if (unreadIds.length > 0) {
        try {
          await apiSendReadReceipt(sessionId, unreadIds)
        } catch {
          // 已读回执失败不阻塞主流程
        }
      }
    },

    /**
     * 针对单条新收到的消息立即发送已读回执
     * 用于当前正在看会话时收到新消息的场景
     */
    async sendReadReceiptForMessage(sessionId, messageId) {
      if (!messageId || String(messageId).startsWith('cmsg_')) return
      try {
        await apiSendReadReceipt(sessionId, [messageId])
      } catch {
        // 不阻塞
      }
    },

    async markGroupRead(sessionId, messageId) {
      try {
        await apiMarkGroupRead(sessionId, messageId)
      } catch {
        // 群已读失败不阻塞
      }
    },

    async handleReadReceipt(sessionId, messageIds) {
      const idSet = new Set(messageIds)
      const msgs = this.messages[sessionId] || []
      for (const msg of msgs) {
        if (idSet.has(msg.id)) {
          msg.deliveryStatus = DeliveryStatus.READ
        }
      }
      await chatDB.updateDeliveryStatus(messageIds, DeliveryStatus.READ)
    },

    /**
     * 按 maxMessageId 批量标记已读
     * 后端推送 {sessionId, readerId, maxMessageId}
     * 把该会话中 id <= maxMessageId 的所有自己发的消息标记为已读
     */
    async handleReadReceiptByMax(sessionId, maxMessageId) {
      const authStore = useAuthStore()
      const msgs = this.messages[sessionId] || []
      const updatedIds = []
      for (const msg of msgs) {
        if (String(msg.senderId) === String(authStore.user?.id) && msg.deliveryStatus !== DeliveryStatus.READ) {
          // 如果有 maxMessageId，只标记 <= maxMessageId 的；否则标记全部
          if (!maxMessageId || msg.id <= maxMessageId) {
            msg.deliveryStatus = DeliveryStatus.READ
            if (msg.id) updatedIds.push(msg.id)
          }
        }
      }
      if (updatedIds.length) {
        await chatDB.updateDeliveryStatus(updatedIds, DeliveryStatus.READ)
      }
    },

    /**
     * 处理 WebSocket MSG_ACK：确认消息发送成功
     * @param {object} data - { clientMsgId, messageId, status? }
     */
    async handleMsgAck(data) {
      const { clientMsgId, messageId, seq } = data

      // 遍历所有内存中的会话找到 pending 消息
      for (const [sessionId, msgs] of Object.entries(this.messages)) {
        const idx = msgs.findIndex(m => m.clientMsgId === clientMsgId)
        if (idx === -1) continue

        const pending = msgs[idx]
        const oldId = pending.id
        const confirmed = {
          ...pending,
          id: messageId,
          seq: seq == null ? pending.seq : String(seq),
          sendStatus: SendStatus.SUCCESS,
          deliveryStatus: DeliveryStatus.SENT
        }
        const persisted = await chatDB.putMessage(confirmed)
        Object.assign(pending, persisted)
        useSessionStore().replacePreviewMessageId(sessionId, oldId, messageId)
        if (persisted.recalled) this.applyRecallProjection(sessionId, persisted)

        // 更新去重 Set
        const set = this._ensureIdSet(sessionId)
        if (oldId) set.ids.delete(oldId)
        set.ids.add(messageId)

        // 删除旧的 pending 记录（id 是 clientMsgId），写入新的确认记录（id 是 messageId）
        if (oldId && oldId !== messageId) {
          await chatDB.deleteMessage(sessionId, oldId)
        }
        return
      }
    },

    /**
     * 将 PendingAckManager 中超时未确认的消息标记为 FAIL
     */
    async markFailedMessages() {
      const failedIds = wsManager.pendingAcks?.getFailedIds?.() || []
      for (const clientMsgId of failedIds) {
        for (const [sessionId, msgs] of Object.entries(this.messages)) {
          const msg = msgs.find(m => m.clientMsgId === clientMsgId)
          if (msg && msg.sendStatus === SendStatus.SENDING) {
            msg.sendStatus = SendStatus.FAIL
            await chatDB.updateMessage(sessionId, msg.id, { sendStatus: SendStatus.FAIL })
            wsManager._pendingAcks.removeFailed(clientMsgId)
            break
          }
        }
      }
    },

    setQuote(message) {
      this.quoteMessage = message
    },

    clearQuote() {
      this.quoteMessage = null
    },

    _enforceLimit(sessionId) {
      const msgs = this.messages[sessionId]
      if (msgs && msgs.length > MAX_CACHE_SIZE) {
        const removed = msgs.splice(0, msgs.length - MAX_CACHE_SIZE)
        // 从去重 Set 中移除被淘汰的消息 id
        const set = this._idSets[sessionId]
        if (set) {
          for (const m of removed) {
            if (m.id) set.ids.delete(m.id)
            if (m.clientMsgId) set.clientIds.delete(m.clientMsgId)
          }
        }
        // 注意：不从 IndexedDB 删除，历史消息保留在磁盘
      }
    },

    /**
     * 重试发送失败的消息
     * 边界处理：消息不存在、非 fail 状态（防止重复重试）
     */
    async retryMessage(sessionId, clientMsgId) {
      const msgs = this.messages[sessionId]
      if (!msgs) return

      const msg = msgs.find(m => m.clientMsgId === clientMsgId)
      if (!msg) return
      // 防止重复重试：只有 fail 状态才允许重试
      if (msg.sendStatus !== SendStatus.FAIL) return

      // 重置为 sending 状态
      msg.sendStatus = SendStatus.SENDING
      await chatDB.updateMessage(sessionId, msg.id, { sendStatus: SendStatus.SENDING })

      const wsPayload = {
        clientMsgId: msg.clientMsgId,
        sessionId,
        type: msg.type,
        content: msg.content,
        senderId: msg.senderId,
        senderName: msg.senderName,
        senderAvatar: msg.senderAvatar,
        quote: msg.quote,
        fileMeta: msg.fileMeta
      }

      if (USE_MOCK) {
        const res = await apiSendMessage(sessionId, wsPayload)
        if (res.code !== 200) {
          msg.sendStatus = SendStatus.FAIL
          await chatDB.updateMessage(sessionId, msg.id, { sendStatus: SendStatus.FAIL })
        }
      } else {
        if (wsManager.isConnected) {
          wsManager.send('SEND_MESSAGE', wsPayload)
        } else {
          try {
            const res = await apiSendMessage(sessionId, wsPayload)
            if (res.code !== 200) {
              msg.sendStatus = SendStatus.FAIL
              await chatDB.updateMessage(sessionId, msg.id, { sendStatus: SendStatus.FAIL })
            } else {
              const serverMsg = res.data
              msg.id = String(serverMsg.messageId || serverMsg.id || msg.id)
              msg.seq = serverMsg.seq == null ? msg.seq : String(serverMsg.seq)
              msg.sendStatus = SendStatus.SUCCESS
              msg.deliveryStatus = DeliveryStatus.SENT
              Object.assign(msg, await chatDB.putMessage(msg))
            }
          } catch {
            msg.sendStatus = SendStatus.FAIL
            await chatDB.updateMessage(sessionId, msg.id, { sendStatus: SendStatus.FAIL })
          }
        }
      }
    },

    /**
     * 离线增量同步：重连后拉取每个会话的增量消息
     * - 遍历所有有消息记录的会话（含已 evict 的）
     * - 每个会话取本地最后一条消息的 id，调 syncMessages 拉增量
     * - 活跃会话同时更新内存，非活跃会话只写 IndexedDB
     * - 并发控制：最多 5 个同时请求
     * - 单个会话失败不影响其他会话
     */
    async syncOfflineMessages(activeSessionId) {
      const allSessionIds = await chatDB.getAllSessionIds()
      if (!allSessionIds.length) return // 本地无消息，跳过增量同步

      const CONCURRENCY = 5
      const results = { synced: 0, failed: 0 }

      // 分批并发执行
      for (let i = 0; i < allSessionIds.length; i += CONCURRENCY) {
        const batch = allSessionIds.slice(i, i + CONCURRENCY)
        const tasks = batch.map(sessionId => this._syncSession(sessionId, activeSessionId))
        const settled = await Promise.allSettled(tasks)
        for (const r of settled) {
          if (r.status === 'fulfilled' && r.value) results.synced++
          else if (r.status === 'rejected') results.failed++
        }
      }

      return results
    },

    /**
     * 同步单个会话的增量消息
     */
    async _syncSession(sessionId, activeSessionId) {
      // 跳过群聊会话，群聊走 _syncGroupSession
      if (sessionId.startsWith('group_')) return false

      const isActive = sessionId === activeSessionId
      let cursor = await chatDB.getSyncCursor(sessionId)
      let synced = false

      // 一次重连可能积压超过服务端单页上限，必须追到 hasMore=false。
      for (let page = 0; page < 100; page++) {
        const res = await apiSyncMessages(sessionId, cursor)
        if (res.code !== 200) throw new Error('私聊增量同步失败')
        const rawMessages = res.data.messages || []
        if (!rawMessages.length) break

        const newMessages = rawMessages.map(m => normalizeMessage(m, sessionId))
        cursor = newMessages[newMessages.length - 1]
        const committed = await chatDB.commitSyncPage(sessionId, newMessages, cursor)
        for (const msg of committed) {
          if (isActive) await this.appendMessage(sessionId, msg)
          else if (msg.recalled) this.applyRecallProjection(sessionId, msg)
        }
        synced = true
        if (!res.data.hasMore) break
      }

      return synced
    },

    /**
     * 群聊离线增量同步：重连后拉取每个群会话的增量消息
     * 逻辑与 syncOfflineMessages 对称，使用 syncGroupMessages API
     * @param {string} activeSessionId 当前活跃会话 ID
     * @param {string[]} groupSessionIds 所有群聊会话 ID 列表
     */
    async syncOfflineGroupMessages(activeSessionId, groupSessionIds) {
      if (!groupSessionIds.length) return

      const CONCURRENCY = 5
      const results = { synced: 0, failed: 0 }

      for (let i = 0; i < groupSessionIds.length; i += CONCURRENCY) {
        const batch = groupSessionIds.slice(i, i + CONCURRENCY)
        const tasks = batch.map(sessionId => this._syncGroupSession(sessionId, activeSessionId))
        const settled = await Promise.allSettled(tasks)
        for (const r of settled) {
          if (r.status === 'fulfilled' && r.value) results.synced++
          else if (r.status === 'rejected') results.failed++
        }
      }

      return results
    },

    /**
     * 同步单个群会话的增量消息
     */
    async _syncGroupSession(sessionId, activeSessionId) {
      // sessionId 是 "group_1"，API 需要纯数字 groupId
      const groupId = sessionId.replace('group_', '')
      const isActive = sessionId === activeSessionId
      let cursor = await chatDB.getSyncCursor(sessionId)
      let synced = false

      for (let page = 0; page < 100; page++) {
        const res = await apiSyncGroupMessages(groupId, cursor)
        if (res.code !== 200) throw new Error('群聊增量同步失败')
        const rawMessages = res.data.messages || []
        if (!rawMessages.length) break

        const newMessages = rawMessages.map(m => normalizeMessage(m, sessionId))
        cursor = newMessages[newMessages.length - 1]
        const committed = await chatDB.commitSyncPage(sessionId, newMessages, cursor)
        for (const msg of committed) {
          if (isActive) await this.appendMessage(sessionId, msg)
          else if (msg.recalled) this.applyRecallProjection(sessionId, msg)
        }
        synced = true
        if (!res.data.hasMore) break
      }

      return synced
    },

    async removeSessionMessages(sessionId) {
      delete this.messages[sessionId]
      delete this.hasMore[sessionId]
      delete this.cursors[sessionId]
      delete this._idSets[sessionId]
      await chatDB.deleteSessionMessages(sessionId)
    }
  }

  // 优化原因：移除 persist 配置，消息不再序列化到 localStorage
  // token/userInfo 仍由 auth store 的 persist: true 管理
})
