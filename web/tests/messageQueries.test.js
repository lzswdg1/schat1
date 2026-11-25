import { beforeEach, describe, expect, it, vi } from 'vitest'
import Dexie from 'dexie'
import db, * as chatDB from '@/db/chatDB.js'

describe('indexed message queries', () => {
  beforeEach(async () => {
    vi.restoreAllMocks()
    await chatDB.clearAll()
  })

  it('decodes only the requested page for recent, older and last-message queries', async () => {
    await chatDB.putMessages(Array.from({ length: 5000 }, (_, index) => ({
      sessionId: 'bulk', id: String(index + 1), timestamp: index, content: 'synthetic'
    })))
    let decoded = 0
    const reading = message => { decoded++; return message }
    db.messages.hook('reading', reading)
    try {
      const recent = await chatDB.getRecentMessages('bulk', 50)
      expect(recent).toHaveLength(50)
      expect(decoded).toBe(50)
      expect(recent[0].timestamp).toBe(4950)
      decoded = 0
      expect((await chatDB.getLastMessage('bulk')).timestamp).toBe(4999)
      expect(decoded).toBe(1)
      decoded = 0
      const older = await chatDB.getMessagesBefore('bulk', recent[0], 20)
      expect(older).toHaveLength(20)
      expect(older[0].timestamp).toBe(4930)
      expect(decoded).toBe(20)
      decoded = 0
      expect(await chatDB.getRecentMessages('bulk', 0)).toEqual([])
      expect(await chatDB.getMessagesBefore('bulk', recent[0], 0)).toEqual([])
      expect(decoded).toBe(0)
    } finally {
      db.messages.hook('reading').unsubscribe(reading)
    }
  })

  it('paginates identical timestamps without omissions or duplicate messages', async () => {
    const messages = Array.from({ length: 11 }, (_, index) => ({
      sessionId: 'ties', id: String(index + 1).padStart(2, '0'), timestamp: 100
    }))
    await chatDB.putMessages([...messages, { sessionId: 'other', id: 'foreign', timestamp: 100 }])
    let page = await chatDB.getRecentMessages('ties', 3)
    const seen = []
    for (let pageNumber = 0; page.length && pageNumber < 12; pageNumber++) {
      seen.unshift(...page.map(message => message.id))
      page = await chatDB.getMessagesBefore('ties', page[0], 3)
    }
    expect(seen).toEqual(messages.map(message => message.id))
    expect(await chatDB.getMessagesBefore('ties', 100, 3)).toEqual([])
  })

  it('filters delivery updates only over matching indexed IDs, including multiple sessions', async () => {
    await chatDB.putMessages([
      ...Array.from({ length: 1000 }, (_, index) => ({
        sessionId: 'bulk', id: String(index + 1), timestamp: index,
        deliveryStatus: index === 500 ? 'read' : 'sent'
      })),
      { sessionId: 'other', id: '1', timestamp: 1, deliveryStatus: 'sent' }
    ])
    const prototype = Object.getPrototypeOf(db.messages.toCollection())
    const original = prototype.filter
    let visited = 0
    const filter = vi.spyOn(prototype, 'filter').mockImplementation(function(predicate) {
      return original.call(this, message => { visited++; return predicate(message) })
    })
    try {
      await chatDB.updateDeliveryStatus(['1', '501', 'missing'], 'delivered')
      expect(visited).toBe(3)
    } finally {
      filter.mockRestore()
    }
    expect((await db.messages.get(['bulk', '1'])).deliveryStatus).toBe('delivered')
    expect((await db.messages.get(['other', '1'])).deliveryStatus).toBe('delivered')
    expect((await db.messages.get(['bulk', '501'])).deliveryStatus).toBe('read')
    expect((await db.messages.get(['bulk', '2'])).deliveryStatus).toBe('sent')
  })

  it('retains compatibility with safely representable legacy numeric message IDs', async () => {
    await chatDB.putMessages([
      { sessionId: 'legacy', id: 42, timestamp: 1, deliveryStatus: 'sent' },
      { sessionId: 'current', id: '42', timestamp: 1, deliveryStatus: 'sent' }
    ])
    await chatDB.updateDeliveryStatus(['42'], 'read')
    expect((await db.messages.get(['legacy', 42])).deliveryStatus).toBe('read')
    expect((await db.messages.get(['current', '42'])).deliveryStatus).toBe('read')
  })

  it('indexes new messages with missing or non-finite timestamps instead of losing them', async () => {
    await chatDB.putMessage({ sessionId: '2', id: 'missing' })
    await chatDB.putMessage({ sessionId: '2', id: 'invalid', timestamp: Infinity })
    expect(await chatDB.getRecentMessages('2')).toHaveLength(2)
    expect((await chatDB.getRecentMessages('2')).every(message => message.timestamp === 0)).toBe(true)
    expect(await chatDB.getLastMessage('empty')).toBeNull()
  })

  it('upgrades version 2 in place and preserves messages and both cursor tables', async () => {
    await db.delete()
    const legacy = new Dexie('im_chat')
    legacy.version(2).stores({
      messages: '[sessionId+id], sessionId, clientMsgId, timestamp',
      cursors: 'sessionId', syncCursors: 'sessionId'
    })
    try {
      await legacy.messages.bulkPut([
        { sessionId: '2', id: 'legacy', content: 'retained' },
        { sessionId: '2', id: 'recalled', timestamp: 2, recalled: true, deliveryRevision: 1, content: '' }
      ])
      await legacy.cursors.put({ sessionId: '2', cursor: 'legacy', hasMore: true })
      await legacy.syncCursors.put({ sessionId: '2', cursor: { id: 'recalled', seq: '5' } })
    } finally {
      legacy.close()
    }
    await db.open()
    expect(db.verno).toBe(3)
    expect(db.messages.schema.indexes.map(index => index.name)).toEqual(expect.arrayContaining(['id', '[sessionId+timestamp+id]']))
    expect(await chatDB.getRecentMessages('2')).toMatchObject([
      { id: 'legacy', timestamp: 0, content: 'retained' },
      { id: 'recalled', recalled: true, deliveryRevision: 1, content: '' }
    ])
    expect(await chatDB.getCursor('2')).toMatchObject({ cursor: 'legacy', hasMore: true })
    expect(await chatDB.getSyncCursor('2')).toEqual({ id: 'recalled', seq: '5' })
  })
})
