import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({
  handlers: new Map(),
  cleanup: null,
  chat: { appendMessage: vi.fn() },
  sessions: { sessions: [{ id: '2' }], activeSessionId: null, updateLastMessage: vi.fn(), incrementUnread: vi.fn() },
  send: vi.fn()
}))

vi.mock('vue', () => ({
  onMounted: callback => callback(),
  onBeforeUnmount: callback => { state.cleanup = callback },
  watch: vi.fn()
}))
vi.mock('vue-router', () => ({ useRouter: () => ({ replace: vi.fn() }) }))
vi.mock('@/api/provider.js', () => ({ USE_MOCK: false }))
vi.mock('@/api/mock/listener.js', () => ({ messageListener: { off: vi.fn(), stopAutoReply: vi.fn() } }))
vi.mock('@/api/websocket.js', () => ({
  registerSessionStore: vi.fn(),
  wsManager: {
    isConnected: true,
    _rawSend: state.send,
    on: (name, callback) => state.handlers.set(name, callback),
    off: vi.fn()
  }
}))
vi.mock('@/stores/chat.js', () => ({ useChatStore: () => state.chat }))
vi.mock('@/stores/session.js', () => ({ useSessionStore: () => state.sessions }))
vi.mock('@/stores/auth.js', () => ({ useAuthStore: () => ({ user: { id: '1' } }) }))
vi.mock('@/stores/contact.js', () => ({ useContactStore: () => ({}) }))
vi.mock('@/stores/notification.js', () => ({ useNotificationStore: () => ({}) }))

import { useMessageListener } from '@/composables/useMessageListener.js'

describe('recipient delivery acknowledgement', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.handlers.clear()
    state.sessions.sessions = [{ id: '2' }]
    state.chat.appendMessage.mockResolvedValue(true)
    useMessageListener()
  })

  afterEach(() => state.cleanup?.())

  const message = { id: '9007199254740993', sessionId: '2', senderId: '2', content: 'hello' }

  it('acknowledges only after persistence resolves', async () => {
    let finishPersistence
    state.chat.appendMessage.mockReturnValue(new Promise(resolve => { finishPersistence = resolve }))
    const received = state.handlers.get('new-message')(message)
    expect(state.send).not.toHaveBeenCalled()
    finishPersistence(true)
    await received
    expect(state.send).toHaveBeenCalledWith({ msgType: 'DELIVERY_ACK', messageId: message.id, deliveryRevision: 0 })
    expect(state.sessions.incrementUnread).toHaveBeenCalledOnce()
  })

  it('does not acknowledge or change unread counts when storage fails', async () => {
    state.chat.appendMessage.mockRejectedValue(new Error('disk full'))
    await state.handlers.get('new-message')(message)
    expect(state.send).not.toHaveBeenCalled()
    expect(state.sessions.incrementUnread).not.toHaveBeenCalled()
  })

  it('acknowledges duplicate delivery again without another unread increment', async () => {
    state.chat.appendMessage.mockResolvedValue(false)
    await state.handlers.get('new-message')(message)
    expect(state.send).toHaveBeenCalledWith({ msgType: 'DELIVERY_ACK', messageId: message.id, deliveryRevision: 0 })
    expect(state.sessions.incrementUnread).not.toHaveBeenCalled()
    expect(state.sessions.updateLastMessage).not.toHaveBeenCalled()
  })

  it('acknowledges group delivery without sending a private delivery receipt', async () => {
    state.sessions.sessions = [{ id: 'group_10' }]
    await state.handlers.get('new-message')({ ...message, sessionId: 'group_10' })
    expect(state.send).toHaveBeenCalledExactlyOnceWith({ msgType: 'DELIVERY_ACK', messageId: message.id, deliveryRevision: 0 })
  })
})
