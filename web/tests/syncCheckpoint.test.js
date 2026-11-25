import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat.js'
import { useSessionStore } from '@/stores/session.js'
import * as chatAPI from '@/api/chat.js'
import * as chatDB from '@/db/chatDB.js'

vi.mock('@/api/provider.js', () => ({ USE_MOCK: false }))

describe('durable synchronization checkpoints', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    setActivePinia(createPinia())
    await chatDB.clearAll()
  })

  it.each([
    ['2', 'syncMessages', '_syncSession'],
    ['group_10', 'syncGroupMessages', '_syncGroupSession']
  ])('does not skip missing messages when realtime delivery is ahead for %s', async (sessionId, apiName, action) => {
    await chatDB.putMessage({ sessionId, id: '102', seq: '102', content: 'ahead', timestamp: 102 })
    const sync = vi.spyOn(chatAPI, apiName).mockResolvedValue({
      code: 200,
      data: { messages: [{ id: '101', seq: '101', content: 'missing' }, { id: '102', seq: '102', content: 'ahead' }], hasMore: false }
    })

    await useChatStore()[action](sessionId, null)

    expect(sync.mock.calls[0][1]).toEqual({ id: '0', seq: '0' })
    expect(await chatDB.hasMessage(sessionId, '101')).toBe(true)
    expect((await chatDB.getSyncCursor(sessionId)).seq).toBe('102')
    await chatDB.putMessage({ sessionId, id: '105', seq: '105', content: 'ahead again', timestamp: 105 })
    sync.mockResolvedValue({ code: 200, data: { messages: [], hasMore: false } })
    await useChatStore()[action](sessionId, null)
    expect(sync.mock.calls[1][1].seq).toBe('102')
  })

  it('keeps the last completed page checkpoint when the next request fails', async () => {
    vi.spyOn(chatAPI, 'syncMessages')
      .mockResolvedValueOnce({ code: 200, data: { messages: [{ id: '1', seq: '1' }], hasMore: true } })
      .mockRejectedValueOnce(new Error('disconnected'))
    await expect(useChatStore()._syncSession('2', null)).rejects.toThrow('disconnected')
    expect(await chatDB.hasMessage('2', '1')).toBe(true)
    expect((await chatDB.getSyncCursor('2')).seq).toBe('1')
  })

  it('rolls back the checkpoint together with an invalid message page', async () => {
    await chatDB.commitSyncPage('2', [{ sessionId: '2', id: '1', seq: '1' }], { id: '1', seq: '1' })
    await expect(chatDB.commitSyncPage('2', [{ sessionId: '2', id: '2', seq: '2' }, { sessionId: '2' }], { id: '2', seq: '2' })).rejects.toThrow()
    expect((await chatDB.getSyncCursor('2')).seq).toBe('1')
    expect(await chatDB.hasMessage('2', '2')).toBe(false)
  })

  it.each([
    ['2', 'syncMessages', '_syncSession'],
    ['group_10', 'syncGroupMessages', '_syncGroupSession']
  ])('does not resurrect a recall when an old sync response arrives for %s', async (sessionId, apiName, action) => {
    const store = useChatStore()
    const original = { id: '123', seq: '1', sessionId, type: 'text', content: 'old body', deliveryRevision: 0 }
    let releaseResponse
    const response = new Promise(resolve => { releaseResponse = resolve })
    let requestStarted
    const started = new Promise(resolve => { requestStarted = resolve })
    vi.spyOn(chatAPI, apiName).mockImplementation(() => {
      requestStarted()
      return response
    })
    const syncing = store[action](sessionId, sessionId)
    await started
    await store.appendMessage(sessionId, { ...original, recalled: true, deliveryRevision: 1 })
    releaseResponse({ code: 200, data: { messages: [original], hasMore: false } })
    await syncing

    expect(store.messages[sessionId][0]).toMatchObject({ recalled: true, deliveryRevision: 1, content: '' })
    expect((await chatDB.getRecentMessages(sessionId))[0]).toMatchObject({ recalled: true, deliveryRevision: 1, content: '' })
    expect(await chatDB.getSyncCursor(sessionId)).toEqual({ id: '123', seq: '1' })
  })

  it('uses the persisted tombstone instead of an old history page for an evicted session', async () => {
    const original = { id: '123', sessionId: '2', type: 'text', content: 'old body', deliveryRevision: 0 }
    await chatDB.putReceivedMessage({ ...original, recalled: true, deliveryRevision: 1 })
    vi.spyOn(chatAPI, 'getHistory').mockResolvedValue({
      code: 200, data: { messages: [original], cursor: '123', hasMore: false }
    })
    const store = useChatStore()
    await store.fetchHistory('2')
    expect(store.messages['2'][0]).toMatchObject({ recalled: true, deliveryRevision: 1, content: '' })
  })

  it.each([
    ['2', 'syncMessages', '_syncSession', false],
    ['group_10', 'syncGroupMessages', '_syncGroupSession', false],
    ['2', 'syncMessages', '_syncSession', true],
    ['group_10', 'syncGroupMessages', '_syncGroupSession', true]
  ])('updates inactive recall projections for %s via %s/%s (cached recall: %s)', async (sessionId, apiName, action, cachedRecall) => {
    const original = { id: '123', seq: '1', sessionId, type: 'text', content: 'old body', timestamp: 1000 }
    const recall = { ...original, recalled: true, deliveryRevision: 1 }
    await chatDB.putReceivedMessage(cachedRecall ? recall : original)
    useSessionStore().sessions = [{ id: sessionId, lastMessageId: '123', lastMessage: 'old body' }]
    vi.spyOn(chatAPI, apiName).mockResolvedValue({
      code: 200, data: { messages: [cachedRecall ? original : recall], hasMore: false }
    })
    const store = useChatStore()
    const projection = vi.spyOn(store, 'applyRecallProjection')
    await store[action](sessionId, null)
    expect(projection).toHaveBeenCalledWith(sessionId, expect.objectContaining({ recalled: true, content: '' }))
    expect(useSessionStore().sessions[0].lastMessage).toBe('[消息已撤回]')
    expect(store.messages[sessionId]).toBeUndefined()
  })
})
