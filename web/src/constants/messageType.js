export const MessageType = {
  TEXT: 'text',
  IMAGE: 'image',
  FILE: 'file',
  SYSTEM: 'system',
  QUOTE: 'quote', // 保留，实际引用通过 message.quote 字段实现
  READ: 'read'    // 已读回执
}

/**
 * 消息发送状态
 */
export const SendStatus = {
  SENDING: 'sending',
  SUCCESS: 'success',
  FAIL: 'fail'
}

/**
 * 消息送达状态
 */
export const DeliveryStatus = {
  SENT: 'sent',         // 已发送（灰色单勾）
  DELIVERED: 'delivered', // 已送达（灰色双勾）
  READ: 'read'          // 已读（蓝色双勾）
}
