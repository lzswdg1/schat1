/**
 * 聊天相关 API 接口
 * 纯接口定义，通过 USE_MOCK 路由到 Mock 或 Real 实现
 */

import { USE_MOCK } from './provider.js'
import http from './http.js'

let _mock = null
async function mock() {
  if (!_mock) _mock = await import('./mock/chat.mock.js')
  return _mock
}

export async function getHistory(sessionId, options) {
  if (USE_MOCK) return (await mock()).getHistory(sessionId, options)
  const { cursor, size = 20 } = options || {}
  return http.get(`/chat/${sessionId}/history`, { params: { cursor, size } })
}

export async function sendMessage(sessionId, payload) {
  if (USE_MOCK) return (await mock()).sendMessage(sessionId, payload)
  return http.post(`/chat/${sessionId}/messages`, payload)
}

export async function recallMessage(sessionId, messageId) {
  if (USE_MOCK) return (await mock()).recallMessage(sessionId, messageId)
  return http.post(`/chat/${sessionId}/messages/${messageId}/recall`)
}

/**
 * 已读回执
 * 后端: POST /api/chat/{sessionId}/read
 * Body: { "messageIds": [1,2,3] }
 */
export async function sendReadReceipt(sessionId, messageIds) {
  if (USE_MOCK) return (await mock()).sendReadReceipt(sessionId, messageIds)
  return http.post(`/chat/${sessionId}/read`, { messageIds })
}

/**
 * 群聊已读
 * 后端: POST /api/group/read?groupId=xxx&messageId=xxx
 */
export async function markGroupRead(sessionId, messageId) {
  if (USE_MOCK) return (await mock()).markGroupRead(sessionId)
  const groupId = String(sessionId).replace(/^group_/, '')
  return http.post('/group/read', null, { params: { groupId, messageId } })
}

/**
 * 文件上传
 * 后端: POST /api/file/upload
 */
export async function uploadFile(file, onProgress) {
  if (USE_MOCK) return (await mock()).uploadFile(file)
  const formData = new FormData()
  formData.append('file', file)
  return http.post('/file/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (e) => {
      if (onProgress && e.total) {
        onProgress(Math.round((e.loaded / e.total) * 100))
      }
    }
  })
}

/**
 * 增量同步（私聊）
 * 新消息优先使用会话 seq，同步旧 IndexedDB 数据时自动回退 lastMsgId。
 */
export async function syncMessages(sessionId, cursor, limit = 50) {
  const lastMsgId = typeof cursor === 'object' ? cursor?.id : cursor
  const lastSeq = typeof cursor === 'object' ? cursor?.seq : null
  if (USE_MOCK) return (await mock()).syncMessages(sessionId, lastMsgId, limit)
  const params = lastSeq != null
    ? { lastSeq, limit }
    : { lastMsgId: lastMsgId || 0, limit }
  return http.get(`/chat/${sessionId}/sync`, { params })
}

/**
 * 增量同步（群聊）
 * 后端: GET /api/group/{groupId}/sync?lastMsgId=xxx&limit=xxx
 */
export async function syncGroupMessages(groupSessionId, cursor, limit = 50) {
  const lastMsgId = typeof cursor === 'object' ? cursor?.id : cursor
  const lastSeq = typeof cursor === 'object' ? cursor?.seq : null
  if (USE_MOCK) return (await mock()).syncGroupMessages(groupSessionId, lastMsgId, limit)
  const params = lastSeq != null
    ? { lastSeq, limit }
    : { lastMsgId: lastMsgId || 0, limit }
  return http.get(`/group/${groupSessionId}/sync`, { params })
}

/**
 * 消息搜索
 * 后端: GET /api/chat/search?keyword=xxx&limit=xxx
 */
export async function searchMessages(keyword, limit = 50) {
  if (USE_MOCK) return (await mock()).searchMessages(keyword, limit)
  return http.get('/chat/search', { params: { keyword, limit } })
}
