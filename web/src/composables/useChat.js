/**
 * 聊天逻辑组合函数
 * 封装消息发送、撤回、引用、图片上传流程
 */

import { ref } from 'vue'
import { useChatStore } from '@/stores/chat.js'
import { useSessionStore } from '@/stores/session.js'
import { useAuthStore } from '@/stores/auth.js'
import { uploadFile } from '@/api/chat.js'
import { MessageType, SendStatus } from '@/constants/messageType.js'

export function useChat() {
  const chatStore = useChatStore()
  const sessionStore = useSessionStore()
  const authStore = useAuthStore()

  const loading = ref(false)
  const loadingMore = ref(false)
  const previewImage = ref(null)
  const uploadProgress = ref(0)
  const isUploading = ref(false)

  async function loadHistory(sessionId) {
    if (!sessionId) return
    // 先从 IndexedDB 恢复缓存到内存
    await chatStore.loadFromDB(sessionId)
    if (chatStore.messages[sessionId]?.length) return
    loading.value = true
    try {
      await chatStore.fetchHistory(sessionId)
    } finally {
      loading.value = false
    }
  }

  async function loadMore(sessionId) {
    if (!sessionId) return false
    if (!chatStore.hasMore[sessionId]) return false
    if (loadingMore.value) return false

    loadingMore.value = true
    try {
      const cursor = chatStore.cursors[sessionId]
      await chatStore.fetchHistory(sessionId, cursor)
      return true
    } finally {
      loadingMore.value = false
    }
  }

  /**
   * 发送消息（支持图片上传流程）
   */
  async function sendMessage(sessionId, payload) {
    if (!sessionId) return

    // 图片消息：先本地预览上屏 → 上传 → 替换 URL → 发送
    if (payload.type === MessageType.IMAGE && payload.file) {
      return sendImageMessage(sessionId, payload)
    }

    const res = await chatStore.sendMessage(sessionId, {
      type: payload.type,
      content: payload.content,
      quoteId: payload.quoteId,
      fileMeta: payload.fileMeta || null
    })

    const latest = chatStore.messages[sessionId]?.at(-1)
    if (latest) sessionStore.updateLastMessage(sessionId, latest)

    return res
  }

  /**
   * 图片消息上传流程
   * 优化原因：使用 axios onUploadProgress 真实进度回调，替代 setInterval 模拟进度
   */
  async function sendImageMessage(sessionId, payload) {
    isUploading.value = true
    uploadProgress.value = 0

    try {
      // 上传文件到文件服务（带真实进度回调）
      const uploadRes = await uploadFile(payload.file, (percent) => {
        uploadProgress.value = percent
      })
      uploadProgress.value = 100

      // 用远程 URL 替换本地预览 URL 发送
      const imageUrl = uploadRes.code === 200
        ? (typeof uploadRes.data === 'string' ? uploadRes.data : uploadRes.data?.url)
        : uploadRes.data?.url
      const res = await chatStore.sendMessage(sessionId, {
        type: MessageType.IMAGE,
        content: imageUrl,
        quoteId: payload.quoteId,
        fileMeta: {
          name: uploadRes.data?.name || payload.file?.name,
          size: uploadRes.data?.size || payload.file?.size,
          mimeType: uploadRes.data?.type || payload.file?.type
        }
      })

      const latest = chatStore.messages[sessionId]?.at(-1)
      if (latest) sessionStore.updateLastMessage(sessionId, { ...latest, content: '[图片]' })

      return res
    } catch {
      return { code: 500, message: '图片上传失败' }
    } finally {
      isUploading.value = false
      uploadProgress.value = 0
    }
  }

  async function recallMessage(sessionId, messageId) {
    return chatStore.recallMessage(sessionId, messageId)
  }

  function setQuote(message) {
    chatStore.setQuote(message)
  }

  function clearQuote() {
    chatStore.clearQuote()
  }

  function openImagePreview(url) {
    previewImage.value = url
  }

  function closeImagePreview() {
    previewImage.value = null
  }

  /**
   * 进入会话时发送已读回执
   */
  async function markAsRead(sessionId) {
    if (!sessionId) return
    const authStore = useAuthStore()

    // 等待消息加载完成
    await chatStore.loadFromDB(sessionId)

    const msgs = chatStore.messages[sessionId] || []
    const unread = msgs.filter(m =>
      m.id && String(m.senderId) !== String(authStore.user?.id) && !m.id.startsWith('cmsg_')
    )
    console.log('[READ] markAsRead sessionId=', sessionId, '总消息数=', msgs.length, '对方未读数=', unread.length)
    if (unread.length === 0) {
      console.log('[READ] 没有需要标记已读的消息，跳过')
      return
    }

    const maxMsg = unread[unread.length - 1]
    const session = sessionStore.sessions.find(s => s.id === sessionId)
    const targetId = session?.targetId || sessionId
    console.log('[READ] 发送已读回执: targetId=', targetId, 'maxMsgId=', maxMsg.id)

    const { wsManager } = await import('@/api/websocket.js')
    if (sessionId.startsWith('group_')) {
      const groupId = sessionId.replace('group_', '')
      if (wsManager.isConnected && maxMsg.id) {
        wsManager._rawSend({
          msgType: 'GROUP_READ',
          fromId: authStore.user?.id,
          toId: groupId,
          messageId: maxMsg.id
        })
      }
      await chatStore.markGroupRead(sessionId, maxMsg.id)
      console.log('[READ] 群聊 HTTP 已读回执已发送')
      return
    }

    // 通过 WebSocket 发送 READ 回执（实时推送给对方）
    if (wsManager.isConnected && maxMsg.id) {
      const readPayload = {
        msgType: 'READ',
        fromId: authStore.user?.id,
        toId: targetId,
        messageId: maxMsg.id
      }
      console.log('[READ] WebSocket 发送 READ:', JSON.stringify(readPayload))
      wsManager._rawSend(readPayload)
    } else {
      console.warn('[READ] WebSocket 未连接或 maxMsgId 为空, connected=', wsManager.isConnected, 'maxMsgId=', maxMsg.id)
    }
    // 同时通过 HTTP 更新数据库
    await chatStore.sendReadReceipt(sessionId)
    console.log('[READ] HTTP 已读回执已发送')
  }

  return {
    loading,
    loadingMore,
    previewImage,
    uploadProgress,
    isUploading,
    loadHistory,
    loadMore,
    sendMessage,
    recallMessage,
    setQuote,
    clearQuote,
    openImagePreview,
    closeImagePreview,
    markAsRead
  }
}
