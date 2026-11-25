import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat.js'
import { useSessionStore } from '@/stores/session.js'
import * as chatAPI from '@/api/chat.js'
import * as chatDB from '@/db/chatDB.js'

describe('recall projections', () => {
  const original = { sessionId: '2', id: '123', content: 'private original', type: 'text', timestamp: 1000 }

  beforeEach(async () => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
    await chatDB.clearAll()
    useSessionStore().sessions = [{ id: '2', lastMessage: original.content, lastMessageId: '123', lastTime: 1000 }]
  })

  it('clears recipient preview and selected quote even for an existing message and rounded server timestamp', async () => {
    const chat = useChatStore()
    await chat.appendMessage('2', original)
    chat.setQuote(original)
    expect(await chat.appendMessage('2', { ...original, timestamp: 0, recalled: true, deliveryRevision: 1 })).toBe(false)
    expect(useSessionStore().sessions[0].lastMessage).toBe('[消息已撤回]')
    expect(chat.quoteMessage).toBeNull()
  })

  it('does not overwrite the preview of a newer message when an older message is recalled', async () => {
    const sessions = useSessionStore()
    sessions.updateLastMessage('2', { id: '124', content: 'newer message', timestamp: 1001 })
    await useChatStore().appendMessage('2', { ...original, recalled: true, deliveryRevision: 1 })
    expect(sessions.sessions[0].lastMessage).toBe('newer message')
  })

  it('clears the local sender preview after successful recall', async () => {
    const chat = useChatStore()
    await chat.appendMessage('2', original)
    vi.spyOn(chatAPI, 'recallMessage').mockResolvedValue({ code: 200 })
    await chat.recallMessage('2', '123')
    expect(useSessionStore().sessions[0].lastMessage).toBe('[消息已撤回]')
  })

  it('reconciles pending preview IDs before applying a persisted recall on a late ACK', async () => {
    const chat = useChatStore()
    const pending = { ...original, id: 'cmsg_pending', clientMsgId: 'cmsg_pending' }
    chat.messages['2'] = [pending]
    chat._trackMessage('2', pending)
    useSessionStore().updateLastMessage('2', pending)
    await chatDB.putReceivedMessage({ ...original, recalled: true, deliveryRevision: 1 })
    await chat.handleMsgAck({ clientMsgId: 'cmsg_pending', messageId: '123', seq: '1' })
    expect(useSessionStore().sessions[0]).toMatchObject({ lastMessageId: '123', lastMessage: '[消息已撤回]' })
  })

  it('filters cached recall tombstones from late remote search pages without discarding unrelated results', async () => {
    await chatDB.putReceivedMessage({ ...original, recalled: true, deliveryRevision: 1 })
    const results = [{ sessionId: '2', messageId: '123' }, { sessionId: 'group_1', messageId: '123' }]
    expect(await chatDB.excludeRecalledSearchResults(results)).toEqual([results[1]])
  })

  it('restores empty-body recall tombstones and their preview after reloading local history', async () => {
    await chatDB.putReceivedMessage({ ...original, recalled: true, deliveryRevision: 1 })
    const chat = useChatStore()
    await chat.loadFromDB('2')
    expect(chat.messages['2']).toHaveLength(1)
    expect(chat.messages['2'][0]).toMatchObject({ id: '123', recalled: true, content: '' })
    expect(useSessionStore().sessions[0].lastMessage).toBe('[消息已撤回]')
  })
})
