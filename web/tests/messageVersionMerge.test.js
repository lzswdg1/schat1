import { beforeEach, describe, expect, it } from 'vitest'
import * as chatDB from '@/db/chatDB.js'

describe('monotonic persisted message lifecycle', () => {
  const original = {
    sessionId: '2', id: '123', seq: '1', type: 'text', timestamp: 1,
    content: 'private original', recalled: false, deliveryRevision: 0,
    quote: { content: 'quoted original' }, fileMeta: { url: 'original-file' },
    deliveryStatus: 'read'
  }
  const recalled = { ...original, recalled: true, deliveryRevision: 1 }
  const writers = [
    ['sync page', message => chatDB.commitSyncPage(message.sessionId, [message], { id: message.id, seq: message.seq })],
    ['history batch', message => chatDB.putMessages([message])],
    ['single write', message => chatDB.putMessage(message)],
    ['realtime delivery', message => chatDB.putReceivedMessage(message)],
    ['partial update', message => chatDB.updateMessage(message.sessionId, message.id, message)]
  ]

  beforeEach(async () => {
    await chatDB.clearAll()
  })

  it.each(writers)('%s cannot restore recalled content, references, or an older revision', async (_, write) => {
    await chatDB.putReceivedMessage(original)
    await chatDB.putReceivedMessage(recalled)
    await write({ ...original, deliveryStatus: 'sent' })
    expect((await chatDB.getRecentMessages('2'))[0]).toMatchObject({
      recalled: true, deliveryRevision: 1, content: '', quote: null, fileMeta: null, deliveryStatus: 'read'
    })
  })

  it('sanitizes a tombstone even when it is the first locally received version', async () => {
    await chatDB.putReceivedMessage(recalled)
    expect((await chatDB.getRecentMessages('2'))[0]).toMatchObject({
      recalled: true, content: '', quote: null, fileMeta: null
    })
  })

  it('keeps revisions monotonic and recall irreversible across duplicate keys in one batch', async () => {
    await chatDB.putMessages([recalled, original, { ...original, deliveryRevision: 2 }])
    expect((await chatDB.getRecentMessages('2'))[0]).toMatchObject({
      recalled: true, deliveryRevision: 2, content: '', quote: null, fileMeta: null
    })
  })

  it('does not downgrade read status when a stale history record is merged', async () => {
    await chatDB.putMessage(original)
    await chatDB.putMessages([{ ...original, deliveryStatus: 'sent' }])
    expect((await chatDB.getRecentMessages('2'))[0].deliveryStatus).toBe('read')
  })

  it('rejects an older non-recall version without dropping newer fields', async () => {
    await chatDB.putMessage({ ...original, deliveryRevision: 2, content: 'newer body' })
    await chatDB.putMessages([{ ...original, deliveryRevision: 1, content: 'older body' }])
    expect((await chatDB.getRecentMessages('2'))[0]).toMatchObject({ deliveryRevision: 2, content: 'newer body' })
  })

  it('allows partial status updates on newer revisions without restoring recalled content', async () => {
    await chatDB.putReceivedMessage(recalled)
    await chatDB.updateMessage('2', '123', { sendStatus: 'success', recalled: false, content: 'stale body' })
    expect((await chatDB.getRecentMessages('2'))[0]).toMatchObject({
      sendStatus: 'success', recalled: true, deliveryRevision: 1, content: ''
    })
  })

  it('serializes concurrent sync and recall transactions without losing the tombstone', async () => {
    await chatDB.putMessage(original)
    await Promise.all([
      chatDB.commitSyncPage('2', [original], { id: '123', seq: '1' }),
      chatDB.putReceivedMessage(recalled)
    ])
    expect((await chatDB.getRecentMessages('2'))[0]).toMatchObject({ recalled: true, deliveryRevision: 1, content: '' })
    expect(await chatDB.getSyncCursor('2')).toEqual({ id: '123', seq: '1' })
  })
})
