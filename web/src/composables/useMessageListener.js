/**
 * 消息推送监听组合函数
 * 集成 WebSocket 事件：消息、送达、已读、好友事件
 */

import { onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { messageListener } from '@/api/mock/listener.js'
import { wsManager, registerSessionStore } from '@/api/websocket.js'
import { USE_MOCK } from '@/api/provider.js'
import { useChatStore } from '@/stores/chat.js'
import { useSessionStore } from '@/stores/session.js'
import { useContactStore } from '@/stores/contact.js'
import { useAuthStore } from '@/stores/auth.js'
import { useNotificationStore } from '@/stores/notification.js'
import { DeliveryStatus } from '@/constants/messageType.js'

export function useMessageListener() {
  const chatStore = useChatStore()
  const sessionStore = useSessionStore()
  const contactStore = useContactStore()
  const authStore = useAuthStore()
  const notificationStore = useNotificationStore()
  const router = useRouter()

  // 注入 sessionStore 到 wsManager，用于解析 sessionId
  registerSessionStore(sessionStore)

  // ---- 新消息 ----
  async function handleNewMessage(message) {
    console.log('[MSG] 收到新消息: sessionId=', message.sessionId, 'senderId=', message.senderId, 'content=', message.content?.slice(0, 20))
    console.log('[MSG] activeSessionId=', sessionStore.activeSessionId)
    // 跳过自己发的消息（本地已有 pending 消息，避免重复）
    if (String(message.senderId) === String(authStore.user?.id)) {
      console.log('[MSG] 跳过自己发的消息')
      return
    }

    let inserted
    try {
      inserted = await chatStore.appendMessage(message.sessionId, message)
    } catch (error) {
      console.error('[MSG] 消息持久化失败，等待服务端重试', error)
      return
    }

    if (wsManager.isConnected && message.id) {
      wsManager._rawSend({ msgType: 'DELIVERY_ACK', messageId: message.id, deliveryRevision: Number(message.deliveryRevision || 0) })
    }
    if (!inserted) return

    // 如果当前正在看这个会话，立即发送已读回执给对方
    if (message.sessionId === sessionStore.activeSessionId) {
      if (message.sessionId?.startsWith('group_')) {
        const groupId = message.sessionId.replace('group_', '')
        if (wsManager.isConnected && message.id) {
          wsManager._rawSend({
            msgType: 'GROUP_READ',
            fromId: authStore.user?.id,
            toId: groupId,
            messageId: message.id
          })
        }
        chatStore.markGroupRead(message.sessionId, message.id)
      } else {
        // 通过 WebSocket 发送 READ 回执（实时性更好）
        if (wsManager.isConnected && message.id) {
          wsManager._rawSend({
            msgType: 'READ',
            fromId: authStore.user?.id,
            toId: message.senderId,
            messageId: message.id
          })
        }
        // 同时通过 HTTP 更新数据库
        chatStore.sendReadReceiptForMessage(message.sessionId, message.id)
      }
    }

    // 如果本地没有该会话，自动创建（对方发来的第一条消息）
    const existingSession = sessionStore.sessions.find(s => s.id === message.sessionId)
    if (!existingSession) {
      const isGroup = message.sessionId?.startsWith('group_')
      const groupId = isGroup ? message.sessionId.replace('group_', '') : ''
      const group = isGroup
        ? contactStore.groups.find(g => String(g.groupId || g.id) === groupId)
        : null
      const friend = !isGroup
        ? contactStore.friends.find(f => String(f.id) === String(message.senderId))
        : null
      const newSession = {
        id: message.sessionId,
        type: isGroup ? 'group' : 'private',
        targetId: isGroup ? groupId : String(message.senderId),
        targetName: isGroup ? (group?.groupName || group?.name || '') : (friend?.nickname || message.senderName || ''),
        targetAvatar: isGroup ? (group?.avatar || '') : (friend?.avatar || message.senderAvatar || ''),
        lastMessage: message.recalled ? '[消息已撤回]' : message.content || '',
        lastMessageId: message.id == null ? null : String(message.id),
        lastTime: message.timestamp || Date.now(),
        unreadCount: 1
      }
      sessionStore.sessions.unshift(newSession)
      // 不立即调 loadSessions，避免后端数据覆盖刚创建的本地会话
    } else {
      sessionStore.updateLastMessage(message.sessionId, message)
      if (message.sessionId !== sessionStore.activeSessionId) {
        sessionStore.incrementUnread(message.sessionId)
      }
    }
  }

  // ---- 消息 ACK（发送确认） ----
  function handleMsgAck(data) {
    chatStore.handleMsgAck(data)
  }

  // ---- 送达回执 ----
  function handleDelivered(data) {
    if (data.messageIds) {
      chatStore.updateDeliveryStatus(data.messageIds, DeliveryStatus.DELIVERED)
    }
  }

  // ---- 已读回执 ----
  // 后端有两种格式：
  // 1. ReadReceiptHandler (WebSocket): {sessionId, messageIds:[...]}
  // 2. ChatController.pushReadReceipt (HTTP触发): {sessionId, readerId, maxMessageId}
  function handleReadReceipt(data) {
    console.log('[READ] 收到已读回执推送:', JSON.stringify(data))
    const sessionId = String(data.sessionId || '')
    if (!sessionId) return

    if (data.maxMessageId && String(data.maxMessageId) !== '0') {
      console.log('[READ] 按 maxMessageId 标记已读: sessionId=', sessionId, 'maxMessageId=', data.maxMessageId)
      chatStore.handleReadReceiptByMax(sessionId, String(data.maxMessageId))
    } else if (data.messageIds && Array.isArray(data.messageIds)) {
      const ids = data.messageIds.map(id => String(id))
      console.log('[READ] 按 messageIds 标记已读: sessionId=', sessionId, 'ids=', ids)
      chatStore.handleReadReceipt(sessionId, ids)
    } else {
      console.log('[READ] 兜底标记全部已读: sessionId=', sessionId)
      chatStore.handleReadReceiptByMax(sessionId, null)
    }
  }

  // ---- 好友事件 ----
  function handleNewFriendRequest(data) {
    contactStore.handleNewFriendRequest(data)
  }

  function handleFriendAccepted(data) {
    contactStore.handleFriendAccepted()
    sessionStore.loadSessions()
  }

  // ---- 群创建通知 ----
  async function handleGroupCreated(data) {
    console.log('[MSG] 收到群创建通知:', data)
    await contactStore.fetchGroups()
    await sessionStore.loadSessions()
  }

  // ---- 群解散通知 ----
  async function handleGroupDismissed(data) {
    console.log('[MSG] 收到群解散通知:', data)
    contactStore.handleGroupDismissed(data)
    const removedId = sessionStore.removeSessionByTarget('group', String(data.groupId))
    if (removedId) await chatStore.removeSessionMessages(removedId)
    notificationStore.show('群聊已被解散', 'warning')
  }

  // ---- 被踢出群通知 ----
  async function handleGroupKicked(data) {
    console.log('[MSG] 收到被踢出群通知:', data)
    contactStore.handleGroupKicked(data)
    const removedId = sessionStore.removeSessionByTarget('group', String(data.groupId))
    if (removedId) await chatStore.removeSessionMessages(removedId)
    notificationStore.show('你已被移出群聊', 'warning')
  }

  // ---- 群成员退出通知 ----
  function handleGroupMemberLeft(data) {
    console.log('[MSG] 收到群成员退出通知:', data)
    contactStore.handleGroupMemberLeft(data)
  }

  function handleFriendDeleted(data) {
    contactStore.handleFriendDeleted(data)
    // 清除会话和消息
    const removedSessionId = sessionStore.removeSessionByTarget('private', data.userId)
    if (removedSessionId) {
      chatStore.removeSessionMessages(removedSessionId)
    }
  }

  // ---- 重连后增量同步 ----
  async function handleReconnected() {
    await sessionStore.loadSessions()
    contactStore.fetchFriendRequests()
    // PendingAckManager.flush() 在 wsManager 内部 onopen 中已先执行
    // 这里执行离线增量同步，补齐断线期间的消息
    try {
      await chatStore.syncOfflineMessages(sessionStore.activeSessionId)
    } catch {
      // 私聊同步失败不阻塞群聊同步
    }
    try {
      const groupSessionIds = sessionStore.groupSessions.map(s => s.id)
      await chatStore.syncOfflineGroupMessages(sessionStore.activeSessionId, groupSessionIds)
    } catch {
      // 群聊同步失败不阻塞其他逻辑
    }
  }

  // ---- 被顶号：提示用户并登出 ----
  // 排除页面刷新场景：刷新时新连接会踢掉旧连接，不应视为被顶号
  let isUnloading = false
  function onBeforeUnload() { isUnloading = true }
  window.addEventListener('beforeunload', onBeforeUnload)

  async function handleKicked() {
    if (isUnloading) return // 页面刷新，忽略
    notificationStore.show('账号在其他设备登录，当前连接已断开', 'error', 0)
    await authStore.logout()
    router.replace('/login')
  }

  onMounted(() => {
    // 注册 WebSocket 事件
    wsManager.on('new-message', handleNewMessage)
    wsManager.on('msg-ack', handleMsgAck)
    wsManager.on('delivered', handleDelivered)
    wsManager.on('read-receipt', handleReadReceipt)
    wsManager.on('new-friend-request', handleNewFriendRequest)
    wsManager.on('friend-accepted', handleFriendAccepted)
    wsManager.on('friend-deleted', handleFriendDeleted)
    wsManager.on('group-created', handleGroupCreated)
    wsManager.on('group-dismissed', handleGroupDismissed)
    wsManager.on('group-kicked', handleGroupKicked)
    wsManager.on('group-member-left', handleGroupMemberLeft)
    wsManager.on('reconnected', handleReconnected)
    wsManager.on('kicked', handleKicked)

    if (USE_MOCK && sessionStore.activeSessionId) {
      messageListener.startAutoReply(sessionStore.activeSessionId)
    }
  })

  watch(
    () => sessionStore.activeSessionId,
    (newId) => {
      if (!USE_MOCK) return
      messageListener.stopAutoReply()
      if (newId) {
        messageListener.startAutoReply(newId)
      }
    }
  )

  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', onBeforeUnload)
    wsManager.off('new-message', handleNewMessage)
    wsManager.off('msg-ack', handleMsgAck)
    wsManager.off('delivered', handleDelivered)
    wsManager.off('read-receipt', handleReadReceipt)
    wsManager.off('new-friend-request', handleNewFriendRequest)
    wsManager.off('friend-accepted', handleFriendAccepted)
    wsManager.off('friend-deleted', handleFriendDeleted)
    wsManager.off('group-created', handleGroupCreated)
    wsManager.off('group-dismissed', handleGroupDismissed)
    wsManager.off('group-kicked', handleGroupKicked)
    wsManager.off('group-member-left', handleGroupMemberLeft)
    wsManager.off('reconnected', handleReconnected)
    wsManager.off('kicked', handleKicked)
    messageListener.off('new-message', handleNewMessage)
    messageListener.stopAutoReply()
  })

  return { wsManager, messageListener }
}
