/**
 * WebSocket 通信层
 * - 自动协议适配（http→ws, https→wss）
 * - 心跳保活（30s HEARTBEAT，10s 超时）
 * - 断线自动重连（指数退避，最大 30s）
 * - 优雅停机识别（close code 1012 跳过退避立即重连）
 * - 被顶号检测（close reason 含 "Kicked" 停止重连）
 * - 可靠投递引擎（pendingAcks 队列，指数退避重试，最多 3 次）
 * - 断线暂停重试，重连后 flush 积压消息
 * - 重连后拉取未读计数
 * - 兼容 Inbox briefContent 格式
 *
 * Mock 桥接通过 installMockBridge 动态注入，本模块不 import 任何 mock 文件。
 */

import { USE_MOCK } from './provider.js'

// sessionStore 引用，由外部注入避免循环依赖
let _sessionStoreRef = null
export function registerSessionStore(store) {
  _sessionStoreRef = store
}
function _getSessionStore() {
  return _sessionStoreRef
}

// ============ 可靠投递引擎 ============

class PendingAckManager {
  constructor(wsSend) {
    this._wsSend = wsSend
    /** @type {Map<string, { payload: object, retries: number, timer: number|null, baseDelay: number }>} */
    this._pending = new Map()
    this._paused = false
  }

  register(clientMsgId, payload) {
    const entry = { payload, retries: 0, timer: null, baseDelay: 3000 }
    this._pending.set(clientMsgId, entry)
    if (!this._paused) {
      this._scheduleRetry(clientMsgId, entry)
    }
  }

  ack(clientMsgId) {
    const entry = this._pending.get(clientMsgId)
    if (entry) {
      if (entry.timer) clearTimeout(entry.timer)
      this._pending.delete(clientMsgId)
    }
    return entry
  }

  pause() {
    this._paused = true
    for (const [, entry] of this._pending) {
      if (entry.timer) {
        clearTimeout(entry.timer)
        entry.timer = null
      }
    }
  }

  flush() {
    this._paused = false
    for (const [clientMsgId, entry] of this._pending) {
      this._wsSend(entry.payload)
      this._scheduleRetry(clientMsgId, entry)
    }
  }

  getFailedIds() {
    const failed = []
    for (const [clientMsgId, entry] of this._pending) {
      if (entry.retries >= 3) {
        failed.push(clientMsgId)
      }
    }
    return failed
  }

  removeFailed(clientMsgId) {
    const entry = this._pending.get(clientMsgId)
    if (entry?.timer) clearTimeout(entry.timer)
    this._pending.delete(clientMsgId)
  }

  _scheduleRetry(clientMsgId, entry) {
    if (entry.retries >= 3) {
      if (entry.timer) clearTimeout(entry.timer)
      entry.timer = null
      return
    }
    const delay = entry.baseDelay * Math.pow(2, entry.retries)
    entry.timer = setTimeout(() => {
      if (this._paused) return
      entry.retries++
      this._wsSend(entry.payload)
      this._scheduleRetry(clientMsgId, entry)
    }, delay)
  }

  destroy() {
    for (const [, entry] of this._pending) {
      if (entry.timer) clearTimeout(entry.timer)
    }
    this._pending.clear()
  }
}


// ============ WebSocket Manager ============

class WebSocketManager {
  constructor() {
    /** @type {WebSocket|null} */
    this._ws = null
    this._url = ''
    this._token = ''
    this._userId = ''
    this._heartbeatTimer = null
    this._heartbeatTimeout = null
    this._reconnectTimer = null
    this._reconnectAttempts = 0
    this._maxReconnectDelay = 30000
    this._shouldReconnect = true
    this._isConnected = false
    this._kicked = false
    this._useMock = USE_MOCK

    /** @type {Map<string, Set<Function>>} */
    this._handlers = new Map()

    this._pendingAcks = new PendingAckManager((payload) => this._rawSend(payload))
  }

  connect(httpUrl, token, userId) {
    this._token = token
    this._userId = userId
    this._shouldReconnect = true
    this._kicked = false
    this._reconnectAttempts = 0

    this._url = httpUrl
      .replace(/^https:/, 'wss:')
      .replace(/^http:/, 'ws:')

    if (this._useMock) {
      this._connectMock()
    } else {
      this._connectReal()
    }
  }

  disconnect() {
    this._shouldReconnect = false
    this._stopHeartbeat()
    this._clearReconnectTimer()
    this._pendingAcks.pause()

    if (this._useMock) {
      this._disconnectMock()
    } else if (this._ws) {
      this._ws.close(1000, 'Client disconnect')
      this._ws = null
    }
    this._isConnected = false
  }

  send(type, data) {
    const sessionId = data?.sessionId || ''
    const isGroup = sessionId.startsWith('group_')

    // 确定 msgType
    let msgType = type
    if (type === 'SEND_MESSAGE') {
      const t = (data?.type || 'text').toUpperCase()
      const base = t === 'IMAGE' ? 'IMAGE' : t === 'FILE' ? 'FILE' : 'TEXT'
      msgType = isGroup ? `GROUP_${base}` : base
    }

    // 群聊：用 groupId；私聊：用 toId
    let toId = null
    let groupId = null
    if (isGroup) {
      groupId = sessionId.replace('group_', '')
    } else {
      toId = data?.toId
      if (!toId && sessionId) {
        const store = _getSessionStore()
        const session = store?.sessions.find(s => s.id === sessionId)
        toId = session ? session.targetId : sessionId
      }
    }

    const payload = {
      msgType,
      clientMsgId: data?.clientMsgId,
      fromId: data?.senderId,
      toId,
      groupId,
      content: data?.content,
      quoteMsgId: data?.quote?.messageId || null,
      quoteContent: data?.quote?.content || null,
      timestamp: Date.now()
    }

    if (data?.clientMsgId) {
      this._pendingAcks.register(data.clientMsgId, payload)
    }

    if (!this._rawSend(payload)) {
      console.warn('[WS] 消息发送失败：WebSocket 未连接', payload.msgType, payload.clientMsgId)
    }
  }

  on(event, handler) {
    if (!this._handlers.has(event)) {
      this._handlers.set(event, new Set())
    }
    this._handlers.get(event).add(handler)
  }

  off(event, handler) {
    this._handlers.get(event)?.delete(handler)
  }

  get isConnected() {
    return this._isConnected
  }

  get isKicked() {
    return this._kicked
  }

  // ---- Internal ----

  /**
   * 根据对方 userId 从 sessionStore 查找会话 ID
   * 私聊场景下 session.targetId === fromId
   * 找不到时回退到 fromId（后续 handleNewMessage 会创建新会话）
   */
  _resolveSessionId(fromId) {
    try {
      // 动态导入避免循环依赖
      const sessionStore = _getSessionStore()
      if (sessionStore) {
        const session = sessionStore.sessions.find(
          s => s.type === 'private' && String(s.targetId) === fromId
        )
        if (session) return session.id
      }
    } catch {
      // store 未初始化
    }
    return fromId
  }

  _emit(event, data) {
    this._handlers.get(event)?.forEach(fn => fn(data))
  }

  _rawSend(payload) {
    if (this._useMock) return true
    if (this._ws?.readyState === WebSocket.OPEN) {
      this._ws.send(JSON.stringify(payload))
      return true
    }
    return false
  }

  // ---- Mock stubs (overridden by installMockBridge) ----

  _connectMock() {
    this._isConnected = true
    this._emit('connected')
  }

  _disconnectMock() {
    this._isConnected = false
  }

  // ---- Real WebSocket ----

  _connectReal() {
    try {
      const wsUrl = `${this._url}/api/ws/chat/${this._userId}?token=${this._token}`
      console.log('[WS] 正在连接:', wsUrl.replace(/token=.*/, 'token=***'))
      this._ws = new WebSocket(wsUrl)

      this._ws.onopen = () => {
        console.log('[WS] 连接成功')
        this._isConnected = true
        this._reconnectAttempts = 0
        this._startHeartbeat()
        this._pendingAcks.flush()
        this._emit('connected')
        this._emit('reconnected')
      }

      this._ws.onmessage = (event) => {
        this._handleMessage(event.data)
      }

      this._ws.onclose = (event) => {
        console.warn('[WS] 连接关闭:', event.code, event.reason)
        this._isConnected = false
        this._stopHeartbeat()
        this._pendingAcks.pause()
        this._emit('disconnected', { code: event.code, reason: event.reason })

        // 鉴权失败：token 已失效或缺失，继续重连只会刷 Vite 代理错误。
        if (event.code === 1008 || event.reason?.includes('Token')) {
          this._shouldReconnect = false
          this._emit('auth-expired')
          return
        }

        // 被顶号：停止重连，通知上层
        if (event.reason?.includes('Kicked')) {
          this._kicked = true
          this._shouldReconnect = false
          this._emit('kicked')
          return
        }

        if (this._shouldReconnect) {
          // 服务端优雅停机（code 1012 或 reason 含"重启"）：跳过退避，立即重连
          if (event.code === 1012 || event.reason?.includes('重启')) {
            this._reconnectAttempts = 0
          }
          this._scheduleReconnect()
        }
      }

      this._ws.onerror = (err) => {
        console.error('[WS] 连接错误:', err)
      }
    } catch {
      this._scheduleReconnect()
    }
  }

  _handleMessage(raw) {
    try {
      let msg = JSON.parse(raw)

      if (msg.briefContent) {
        msg = this._parseBriefContent(msg)
      }

      // 兼容后端 msgType 和前端 type 两种字段名
      const msgType = msg.msgType || msg.type || ''

      if (msgType === 'HEARTBEAT_ACK' || msgType === 'PONG') {
        this._clearHeartbeatTimeout()
        return
      }

      // ACK：后端格式是扁平的 {msgType:"ACK", clientMsgId, messageId, status}
      if (msgType === 'ACK' || msgType === 'MSG_ACK') {
        const ackData = msg.data || msg // 兼容扁平和嵌套格式
        if (ackData.clientMsgId) {
          const entry = this._pendingAcks.ack(ackData.clientMsgId)
          if (entry) {
            this._emit('msg-ack', {
              clientMsgId: ackData.clientMsgId,
              messageId: String(ackData.messageId || ''),
              seq: ackData.seq == null ? null : String(ackData.seq),
              status: ackData.status || 'success'
            })
          }
        }
        return
      }

      if (msgType === 'DELIVERED') { this._emit('delivered', msg.data || msg); return }
      if (msgType === 'READ_RECEIPT') {
        const d = msg.data || msg
        this._emit('read-receipt', {
          sessionId: String(d.sessionId || ''),
          readerId: String(d.readerId || ''),
          maxMessageId: String(d.maxMessageId || ''),
          messageIds: d.messageIds || null
        })
        return
      }
      if (msgType === 'NEW_FRIEND_REQUEST') { this._emit('new-friend-request', msg.data || msg); return }
      if (msgType === 'FRIEND_ACCEPTED') { this._emit('friend-accepted', msg.data || msg); return }
      if (msgType === 'FRIEND_DELETED') { this._emit('friend-deleted', msg.data || msg); return }
      if (msgType === 'GROUP_CREATED') { this._emit('group-created', msg.data || msg); return }
      if (msgType === 'GROUP_DISMISSED') { this._emit('group-dismissed', msg.data || msg); return }
      if (msgType === 'GROUP_KICKED') { this._emit('group-kicked', msg.data || msg); return }
      if (msgType === 'GROUP_MEMBER_LEFT') { this._emit('group-member-left', msg.data || msg); return }
      if (msgType === 'NEW_MESSAGE') {
        // NEW_MESSAGE 可能包含和 TEXT/IMAGE/FILE 相同的 ChatMsgDTO 格式
        const raw = msg.data || msg
        raw.deliveryRevision = Number(msg.deliveryRevision ?? raw.deliveryRevision ?? 0)
        if (raw.fromId && raw.msgType) {
          // ChatMsgDTO 格式，需要做字段映射
          const innerType = (raw.msgType || 'text').toLowerCase()
          const resolvedSessionId = raw.groupId
            ? String(raw.groupId)
            : this._resolveSessionId(String(raw.fromId))
          this._emit('new-message', {
            id: String(raw.messageId || raw.id || ''),
            seq: raw.seq == null ? null : String(raw.seq),
            deliveryRevision: raw.deliveryRevision,
            sessionId: resolvedSessionId,
            type: innerType,
            senderId: String(raw.fromId),
            content: raw.content || '',
            timestamp: raw.timestamp || Date.now(),
            recalled: false,
            quote: raw.quoteMsgId ? { messageId: String(raw.quoteMsgId), content: raw.quoteContent || '' } : null,
            clientMsgId: raw.clientMsgId || null
          })
        } else {
          // 已经是前端格式
          this._emit('new-message', raw)
        }
        return
      }
      if (msgType === 'UNREAD_COUNTS') { this._emit('unread-counts', msg.data || msg); return }

      // 后端推送的聊天消息直接用 TEXT/IMAGE/FILE 作为 msgType
      // 后端格式: {fromId, toId, msgType, content, messageId, seq, clientMsgId, ...}
      // 需要转换为前端 store 期望的格式
      if (msgType === 'TEXT' || msgType === 'IMAGE' || msgType === 'FILE') {
        const raw = msg.data || msg
        console.log('[WS] 收到聊天消息:', msgType, 'fromId:', raw.fromId, 'messageId:', raw.messageId)
        // sessionId 需要从 sessionStore 中查找：
        // 私聊场景下 targetId === fromId 的会话就是对应会话
        // 群聊场景下 groupId 就是 sessionId 的一部分
        const resolvedSessionId = raw.groupId
          ? String(raw.groupId)
          : this._resolveSessionId(String(raw.fromId))
        this._emit('new-message', {
          id: String(raw.messageId || raw.id || ''),
          seq: raw.seq == null ? null : String(raw.seq),
          sessionId: resolvedSessionId,
          type: (raw.msgType || 'text').toLowerCase(),
          senderId: String(raw.fromId),
          content: raw.content || '',
          timestamp: raw.timestamp || Date.now(),
          recalled: false,
          quote: raw.quoteMsgId ? { messageId: String(raw.quoteMsgId), content: raw.quoteContent || '' } : null,
          clientMsgId: raw.clientMsgId || null
        })
        return
      }

      // 未识别的消息类型
      console.warn('[WS] 未识别的消息类型:', msgType, msg)
    } catch {
      // ignore malformed messages
    }
  }

  _parseBriefContent(msg) {
    return {
      type: 'NEW_MESSAGE',
      data: {
        id: msg.messageId || msg.id,
        sessionId: msg.sessionId || msg.conversationId,
        type: msg.contentType || 'text',
        senderId: msg.senderId || msg.fromUserId,
        senderName: msg.senderName || msg.fromUserName,
        senderAvatar: msg.senderAvatar || '',
        content: msg.briefContent || msg.content || '',
        timestamp: msg.timestamp || Date.now(),
        recalled: false,
        quote: null,
        fileMeta: msg.fileMeta || null
      }
    }
  }

  // ---- Heartbeat ----

  _startHeartbeat() {
    this._stopHeartbeat()
    this._heartbeatTimer = setInterval(() => {
      if (this._ws?.readyState === WebSocket.OPEN) {
        this._ws.send(JSON.stringify({ msgType: 'HEARTBEAT' }))
        this._heartbeatTimeout = setTimeout(() => {
          this._ws?.close(4000, 'Heartbeat timeout')
        }, 10000)
      }
    }, 30000)
  }

  _stopHeartbeat() {
    if (this._heartbeatTimer) {
      clearInterval(this._heartbeatTimer)
      this._heartbeatTimer = null
    }
    this._clearHeartbeatTimeout()
  }

  _clearHeartbeatTimeout() {
    if (this._heartbeatTimeout) {
      clearTimeout(this._heartbeatTimeout)
      this._heartbeatTimeout = null
    }
  }

  // ---- Reconnect ----

  _scheduleReconnect() {
    if (!this._shouldReconnect) return
    this._clearReconnectTimer()

    const delay = Math.min(1000 * Math.pow(2, this._reconnectAttempts), this._maxReconnectDelay)
    this._reconnectAttempts++

    this._reconnectTimer = setTimeout(() => {
      this._connectReal()
    }, delay)
  }

  _clearReconnectTimer() {
    if (this._reconnectTimer) {
      clearTimeout(this._reconnectTimer)
      this._reconnectTimer = null
    }
  }

  destroy() {
    this.disconnect()
    this._pendingAcks.destroy()
    this._handlers.clear()
  }
}

// ============ 创建单例并按需安装 Mock 桥接 ============

export const wsManager = new WebSocketManager()

/**
 * 安装 Mock 桥接（由 main.js 或初始化逻辑调用）
 */
export async function initWebSocket() {
  if (USE_MOCK) {
    const { installMockBridge } = await import('./mock/websocket.mock.js')
    installMockBridge(wsManager)
  }
}
