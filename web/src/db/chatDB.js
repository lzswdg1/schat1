/**
 * IndexedDB 消息持久化层（基于 Dexie.js）
 * 优化原因：替代 localStorage 序列化，避免 5-10MB 限制和大对象序列化性能问题
 *
 * 只存储消息数据，token/userInfo 仍走 localStorage（数据量小无需迁移）
 */

import Dexie from 'dexie'

const db = new Dexie('im_chat')

db.version(1).stores({
  // sessionId + id 联合索引，支持按会话查询和按 id 去重
  messages: '[sessionId+id], sessionId, clientMsgId, timestamp',
  // 游标和 hasMore 元信息
  cursors: 'sessionId'
})

db.version(2).stores({
  messages: '[sessionId+id], sessionId, clientMsgId, timestamp',
  cursors: 'sessionId',
  syncCursors: 'sessionId'
})

db.version(3).stores({
  messages: '[sessionId+id], sessionId, clientMsgId, timestamp, id, [sessionId+timestamp+id]'
}).upgrade(transaction => transaction.table('messages').toCollection().modify(message => {
  message.timestamp = normalizedTimestamp(message.timestamp)
}))

const deliveryStatusOrder = { sent: 0, delivered: 1, read: 2 }

function normalizedTimestamp(value) {
  const timestamp = Number(value ?? 0)
  return Number.isFinite(timestamp) ? timestamp : 0
}

function mergeMessage(existing, incoming) {
  const existingRevision = Number(existing?.deliveryRevision || 0)
  const incomingRevision = Number(incoming.deliveryRevision || 0)
  const merged = existing && incomingRevision < existingRevision
    ? { ...existing }
    : { ...existing, ...incoming }
  if (existing?.deliveryStatus &&
      (deliveryStatusOrder[existing.deliveryStatus] ?? -1) > (deliveryStatusOrder[merged.deliveryStatus] ?? -1)) {
    merged.deliveryStatus = existing.deliveryStatus
  }
  if (existing?.recalled || incoming.recalled) {
    merged.recalled = true
    merged.content = ''
    merged.quote = null
    merged.fileMeta = null
    merged.deliveryRevision = Math.max(existingRevision, incomingRevision)
  }
  merged.timestamp = normalizedTimestamp(merged.timestamp)
  return merged
}

async function mergeMessages(messages) {
  const existing = await db.messages.bulkGet(messages.map(message => [message.sessionId, message.id]))
  const mergedByKey = new Map()
  for (const [index, message] of messages.entries()) {
    const key = JSON.stringify([message.sessionId, message.id])
    mergedByKey.set(key, mergeMessage(mergedByKey.get(key) || existing[index], message))
  }
  const merged = [...mergedByKey.values()]
  await db.messages.bulkPut(merged)
  return merged
}

export async function getSyncCursor(sessionId) {
  const checkpoint = await db.syncCursors.get(sessionId)
  return checkpoint?.cursor || { id: '0', seq: '0' }
}

export async function commitSyncPage(sessionId, messages, cursor) {
  return db.transaction('rw', db.messages, db.syncCursors, async () => {
    const merged = await mergeMessages(messages)
    await db.syncCursors.put({ sessionId, cursor: { id: cursor.id, seq: cursor.seq } })
    return merged
  })
}

/**
 * 批量写入消息（自动去重，基于 sessionId+id 联合主键）
 */
export async function putMessages(messages) {
  if (!messages.length) return []
  return db.transaction('rw', db.messages, () => mergeMessages(messages))
}

/**
 * 写入单条消息
 */
export async function putMessage(message) {
  const [merged] = await putMessages([message])
  return merged
}

export async function putReceivedMessage(message) {
  return db.transaction('rw', db.messages, async () => {
    const existing = await db.messages.get([message.sessionId, message.id])
    const merged = mergeMessage(existing, message)
    await db.messages.put(merged)
    return { inserted: !existing, message: merged }
  })
}

/**
 * 按会话加载最近 N 条消息
 * 使用会话、时间和消息 ID 复合索引，限制读取量后反转为升序
 */
export async function getRecentMessages(sessionId, limit = 50) {
  if (!Number.isSafeInteger(limit) || limit <= 0) return []
  const msgs = await db.messages
    .where('[sessionId+timestamp+id]')
    .between([sessionId, Dexie.minKey, Dexie.minKey], [sessionId, Infinity, Dexie.maxKey], true, true)
    .reverse()
    .limit(limit)
    .toArray()

  return msgs.reverse()
}

/**
 * 按会话加载 cursor 之前的历史消息
 */
export async function getMessagesBefore(sessionId, cursor, limit = 20) {
  if (!Number.isSafeInteger(limit) || limit <= 0) return []
  const timestamp = Number(typeof cursor === 'object' ? cursor?.timestamp : cursor)
  if (!Number.isFinite(timestamp)) return []
  const id = typeof cursor === 'object' ? cursor?.id ?? Dexie.minKey : Dexie.minKey
  const msgs = await db.messages
    .where('[sessionId+timestamp+id]')
    .between([sessionId, Dexie.minKey, Dexie.minKey], [sessionId, timestamp, id], true, false)
    .reverse()
    .limit(limit)
    .toArray()

  return msgs.reverse()
}

/**
 * 按 id 查找消息（去重用）
 */
export async function hasMessage(sessionId, messageId) {
  const count = await db.messages
    .where('[sessionId+id]')
    .equals([sessionId, messageId])
    .count()
  return count > 0
}

/**
 * 按 clientMsgId 查找消息
 */
export async function findByClientMsgId(sessionId, clientMsgId) {
  return db.messages
    .where('clientMsgId')
    .equals(clientMsgId)
    .filter(m => m.sessionId === sessionId)
    .first()
}

/**
 * 更新单条消息的部分字段
 */
export async function updateMessage(sessionId, messageId, changes) {
  return db.transaction('rw', db.messages, async () => {
    const existing = await db.messages.get([sessionId, messageId])
    if (!existing) return
    const merged = mergeMessage(existing, { ...existing, ...changes, sessionId, id: messageId })
    await db.messages.put(merged)
    return merged
  })
}

/**
 * 批量更新消息的送达状态
 * 按消息 ID 索引定位，送达状态不倒退
 */
export async function updateDeliveryStatus(messageIds, status) {
  const idSet = new Set(messageIds.map(String))
  if (!idSet.size) return
  const keys = [...idSet]
  for (const id of idSet) {
    const numeric = Number(id)
    if (Number.isSafeInteger(numeric) && String(numeric) === id) keys.push(numeric)
  }
  await db.messages
    .where('id')
    .anyOf(keys)
    .filter(message => (deliveryStatusOrder[status] ?? -1) >= (deliveryStatusOrder[message.deliveryStatus] ?? -1))
    .modify({ deliveryStatus: status })
}

/**
 * 删除单条消息（ACK 后清理旧 pending 记录）
 */
export async function deleteMessage(sessionId, messageId) {
  await db.messages.where('[sessionId+id]').equals([sessionId, messageId]).delete()
}

/**
 * 删除某会话的所有消息
 */
export async function deleteSessionMessages(sessionId) {
  await db.messages.where('sessionId').equals(sessionId).delete()
  await db.cursors.delete(sessionId)
  await db.syncCursors.delete(sessionId)
}

/**
 * 保存游标信息
 */
export async function saveCursor(sessionId, cursor, hasMore) {
  await db.cursors.put({ sessionId, cursor, hasMore })
}

/**
 * 读取游标信息
 */
export async function getCursor(sessionId) {
  return db.cursors.get(sessionId)
}

/**
 * 获取某会话的最后一条消息（按 timestamp 取最新的一条）
 */
export async function getLastMessage(sessionId) {
  const [message] = await getRecentMessages(sessionId, 1)
  return message || null
}

/**
 * 获取所有有消息记录的会话 ID（去重）
 */
export async function getAllSessionIds() {
  const keys = await db.messages.orderBy('sessionId').uniqueKeys()
  return keys
}

/**
 * 本地消息搜索：对 content 字段做模糊匹配（仅文本消息）
 */
export async function searchMessages(keyword, limit = 50) {
  if (!keyword) return []
  const lowerKw = keyword.toLowerCase()
  const all = await db.messages
    .filter(m => m.type === 'text' && !m.recalled && m.content && m.content.toLowerCase().includes(lowerKw))
    .limit(limit)
    .toArray()
  return all
}

export async function excludeRecalledSearchResults(results) {
  const stored = await db.messages.bulkGet(results.map(result => [String(result.sessionId), String(result.messageId)]))
  return results.filter((result, index) => !stored[index]?.recalled)
}

/**
 * 清空所有消息数据（登出时调用）
 */
export async function clearAll() {
  await db.messages.clear()
  await db.cursors.clear()
  await db.syncCursors.clear()
}

export default db
