/**
 * 聊天相关 Mock 实现
 */

import { mockDB, persistDB } from './data.js'
import { delay, mockResponse } from './helpers.js'
import { MessageType, SendStatus, DeliveryStatus } from '@/constants/messageType.js'

import { wsManager } from '../websocket.js'
import { installMockBridge } from './websocket.mock.js'

// Ensure mock bridge is installed
if (!wsManager.mockAck) {
  installMockBridge(wsManager)
}

export async function getHistory(sessionId, { cursor, size = 20 } = {}) {
  await delay()

  const allMessages = mockDB.messages.get(sessionId) || []
  let endIndex = allMessages.length

  if (cursor) {
    const cursorIndex = allMessages.findIndex(m => m.id === cursor)
    if (cursorIndex !== -1) endIndex = cursorIndex
  }

  const startIndex = Math.max(0, endIndex - size)
  const messages = allMessages.slice(startIndex, endIndex).map(m => ({ ...m }))
  const hasMore = startIndex > 0
  const nextCursor = hasMore ? allMessages[startIndex].id : null

  return mockResponse({ messages, cursor: nextCursor, hasMore })
}

export async function sendMessage(sessionId, payload) {
  await delay()

  const messageId = `msg_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`

  const newMessage = {
    id: messageId,
    clientMsgId: payload.clientMsgId || messageId,
    sessionId,
    type: payload.type || MessageType.TEXT,
    senderId: payload.senderId,
    senderName: payload.senderName,
    senderAvatar: payload.senderAvatar,
    content: payload.content,
    timestamp: Date.now(),
    recalled: false,
    quote: payload.quote || null,
    fileMeta: payload.fileMeta || null,
    sendStatus: SendStatus.SUCCESS,
    deliveryStatus: DeliveryStatus.SENT
  }

  if (!mockDB.messages.has(sessionId)) {
    mockDB.messages.set(sessionId, [])
  }

  const existing = mockDB.messages.get(sessionId)
  if (!existing.some(m => m.clientMsgId === newMessage.clientMsgId)) {
    existing.push(newMessage)
  }

  persistDB()

  // 模拟 ACK + 送达回执
  if (payload.clientMsgId) {
    setTimeout(() => {
      wsManager.mockAck(payload.clientMsgId, messageId)
    }, 100)
  }
  setTimeout(() => {
    wsManager.mockDelivered([messageId])
  }, 1000 + Math.random() * 1000)

  return mockResponse({ ...newMessage })
}

export async function recallMessage(sessionId, messageId) {
  await delay()

  const messages = mockDB.messages.get(sessionId)
  if (!messages) return mockResponse(null, 400, '会话不存在')

  const message = messages.find(m => m.id === messageId)
  if (!message) return mockResponse(null, 400, '消息不存在')

  message.recalled = true
  persistDB()

  return mockResponse({ success: true })
}

export async function sendReadReceipt(sessionId, messageIds) {
  await delay()

  const messages = mockDB.messages.get(sessionId) || []
  for (const msg of messages) {
    if (messageIds.includes(msg.id)) {
      msg.deliveryStatus = DeliveryStatus.READ
    }
  }
  persistDB()

  setTimeout(() => {
    wsManager.mockReadReceipt(sessionId, messageIds)
  }, 200)

  return mockResponse({ success: true })
}

export async function markGroupRead(sessionId) {
  await delay()
  const messages = mockDB.messages.get(sessionId) || []
  for (const msg of messages) {
    if (msg.deliveryStatus !== DeliveryStatus.READ) {
      msg.deliveryStatus = DeliveryStatus.READ
    }
  }
  persistDB()
  return mockResponse({ success: true })
}

export async function uploadFile(file) {
  await delay()
  const result = {
    url: `https://mock-cdn.example.com/files/${Date.now()}_${file.name}`,
    name: file.name,
    size: file.size,
    type: file.type
  }
  return mockResponse(result)
}

/**
 * 增量同步 Mock：返回 lastMsgId 之后的所有消息
 */
export async function syncMessages(sessionId, lastMsgId, limit = 50) {
  await delay()

  const allMessages = mockDB.messages.get(sessionId) || []

  // 游标为 0 表示本地尚无消息，从群成员可见历史的第一页开始同步。
  if (!lastMsgId || String(lastMsgId) === '0') {
    const messages = allMessages.slice(0, limit).map(m => ({ ...m }))
    return mockResponse({ messages, hasMore: limit < allMessages.length })
  }

  const lastIdx = allMessages.findIndex(m => m.id === lastMsgId)
  // lastMsgId 不存在于服务端，返回空
  if (lastIdx === -1) {
    return mockResponse({ messages: [], hasMore: false })
  }

  const newMessages = allMessages.slice(lastIdx + 1, lastIdx + 1 + limit).map(m => ({ ...m }))
  const hasMore = lastIdx + 1 + limit < allMessages.length

  return mockResponse({ messages: newMessages, hasMore })
}

/**
 * 群聊增量同步 Mock：逻辑与私聊 syncMessages 对称
 */
export async function syncGroupMessages(groupSessionId, lastMsgId, limit = 50) {
  // 群消息和私聊消息共用 mockDB.messages，逻辑完全一致
  return syncMessages(groupSessionId, lastMsgId, limit)
}

/**
 * 消息搜索 Mock：遍历所有会话的消息，按关键词匹配文本消息
 */
export async function searchMessages(keyword, limit = 50) {
  await delay()

  if (!keyword) return mockResponse([])

  const lowerKw = keyword.toLowerCase()
  const results = []

  // 构建 sessionId → sessionName 映射
  const sessionNameMap = {}
  for (const [, userSessions] of mockDB.sessions) {
    for (const s of userSessions) {
      sessionNameMap[s.id] = s.targetName
    }
  }

  for (const [sessionId, msgs] of mockDB.messages) {
    for (const m of msgs) {
      if (m.type !== MessageType.TEXT || m.recalled) continue
      if (!m.content || !m.content.toLowerCase().includes(lowerKw)) continue
      results.push({
        sessionId,
        messageId: m.id,
        fromId: m.senderId,
        senderName: m.senderName,
        content: m.content,
        createTime: m.timestamp,
        sessionName: sessionNameMap[sessionId] || sessionId
      })
      if (results.length >= limit) break
    }
    if (results.length >= limit) break
  }

  // 按时间降序
  results.sort((a, b) => b.createTime - a.createTime)

  return mockResponse(results)
}
