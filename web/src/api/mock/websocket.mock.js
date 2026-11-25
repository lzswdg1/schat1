/**
 * WebSocket Mock 桥接
 * 将 messageListener 的事件桥接到 WebSocketManager
 */

import { messageListener } from './listener.js'

/**
 * 为 WebSocketManager 安装 Mock 方法
 * @param {object} wsManager - WebSocketManager 实例
 */
export function installMockBridge(wsManager) {
  let mockMessageHandler = null

  wsManager._connectMock = function () {
    this._isConnected = true
    this._reconnectAttempts = 0

    mockMessageHandler = (message) => {
      this._emit('new-message', message)
    }
    messageListener.on('new-message', mockMessageHandler)

    this._emit('connected')
  }

  wsManager._disconnectMock = function () {
    if (mockMessageHandler) {
      messageListener.off('new-message', mockMessageHandler)
      mockMessageHandler = null
    }
    messageListener.stopAutoReply()
    this._isConnected = false
  }

  wsManager.mockAck = function (clientMsgId, messageId) {
    this._pendingAcks.ack(clientMsgId)
    this._emit('msg-ack', { clientMsgId, messageId })
  }

  wsManager.mockDelivered = function (messageIds) {
    this._emit('delivered', { messageIds })
  }

  wsManager.mockReadReceipt = function (sessionId, messageIds) {
    this._emit('read-receipt', { sessionId, messageIds })
  }

  wsManager.mockFriendEvent = function (type, data) {
    this._emit(type, data)
  }
}
