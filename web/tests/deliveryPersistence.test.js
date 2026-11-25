import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat.js'
import * as chatDB from '@/db/chatDB.js'
import * as chatAPI from '@/api/chat.js'

describe('durable incoming messages', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
    await chatDB.clearAll()
  })

  const message = { id: '123', sessionId: '2', senderId: '2', content: 'hello', deliveryStatus: 'read' }

  it('storage failure does not leave an in-memory duplicate that can later be acknowledged', async () => {
    const store = useChatStore()
    vi.spyOn(chatDB, 'putReceivedMessage').mockRejectedValueOnce(new Error('quota'))
    await expect(store.appendMessage('2', message)).rejects.toThrow('quota')
    expect(store.messages['2'] || []).toHaveLength(0)
    expect(await store.appendMessage('2', message)).toBe(true)
    expect(await chatDB.hasMessage('2', '123')).toBe(true)
  })

  it('concurrent retries persist one message and do not downgrade already-read state', async () => {
    const store = useChatStore()
    const results = await Promise.all([store.appendMessage('2', message), store.appendMessage('2', message)])
    expect(results.filter(Boolean)).toHaveLength(1)
    expect(store.messages['2']).toHaveLength(1)
    await store.appendMessage('2', { ...message, deliveryStatus: 'sent' })
    await store.updateDeliveryStatus(['123'], 'delivered')
    expect(store.messages['2'][0].deliveryStatus).toBe('read')
    expect((await chatDB.getRecentMessages('2'))[0].deliveryStatus).toBe('read')
  })

  it('replaces a persisted message with a recall and never resurrects its old body', async () => {
    const store = useChatStore()
    await store.appendMessage('2', message)
    expect(await store.appendMessage('2', { ...message, content: '', recalled: true, deliveryRevision: 1 })).toBe(false)
    expect(store.messages['2'][0].recalled).toBe(true)
    expect(store.messages['2'][0].content).toBe('')
    await store.appendMessage('2', { ...message, recalled: false, deliveryRevision: 0 })
    expect(store.messages['2']).toHaveLength(1)
    expect(store.messages['2'][0].recalled).toBe(true)
    expect(store.messages['2'][0].content).toBe('')
    expect((await chatDB.getRecentMessages('2'))[0].content).toBe('')
  })

  it('uses the persisted recall when a late sender acknowledgement confirms an optimistic message', async () => {
    const store = useChatStore()
    const pending = { ...message, id: 'cmsg_pending', clientMsgId: 'cmsg_pending', sendStatus: 'sending' }
    store.messages['2'] = [pending]
    store._trackMessage('2', pending)
    await chatDB.putMessage(pending)
    await chatDB.putReceivedMessage({ ...message, clientMsgId: 'cmsg_pending', recalled: true, deliveryRevision: 1 })

    await store.handleMsgAck({ clientMsgId: 'cmsg_pending', messageId: '123', seq: '1' })

    expect(store.messages['2'][0]).toMatchObject({ id: '123', recalled: true, deliveryRevision: 1, content: '' })
    expect(await chatDB.hasMessage('2', 'cmsg_pending')).toBe(false)
    expect((await chatDB.getRecentMessages('2'))[0].content).toBe('')
  })

  it('clears content and references from memory and storage after a successful local recall', async () => {
    const store = useChatStore()
    await store.appendMessage('2', { ...message, quote: { content: 'quote' }, fileMeta: { url: 'file' } })
    vi.spyOn(chatAPI, 'recallMessage').mockResolvedValue({ code: 200 })

    await store.recallMessage('2', '123')

    expect(store.messages['2'][0]).toMatchObject({ recalled: true, content: '', quote: null, fileMeta: null })
    expect((await chatDB.getRecentMessages('2'))[0]).toMatchObject({ recalled: true, content: '', quote: null, fileMeta: null })
  })
})
