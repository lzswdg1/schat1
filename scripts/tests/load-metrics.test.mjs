import assert from 'node:assert/strict'
import { test } from 'node:test'
import { distribution, generatorLagAbort, LoadTracker, loadOptions, stageVerdict, verifyDatabase } from '../lib/load-metrics.mjs'

const environment = { SCHAT_LOAD_MYSQL_URL: 'jdbc:mysql://127.0.0.1:33671/schat_load?useSSL=false', SCHAT_LOAD_REDIS_PORT: '33672' }
const senderId = '9007199254740993'
const receiverId = '9007199254740995'

function fixture(count = 1) {
  const tracker = new LoadTracker(128)
  for (let index = 0; index < count; index++) {
    tracker.offer({ clientId: `load-test-s1-${index}`, stage: 's1', senderId, receiverId, scheduledAt: index * 100 + 5, sentAt: index * 100 + 10 })
  }
  return tracker
}

function acknowledgement(index = 0, status = 'success') {
  return { type: 'MSG_ACK', data: { clientMsgId: `load-test-s1-${index}`, messageId: String(2111849055417860097n + BigInt(index)), seq: String(index + 1), status } }
}

function delivery(tracker, index = 0) {
  return { type: 'NEW_MESSAGE', deliveryId: String(100 + index), deliveryRevision: 0, data: {
    id: String(2111849055417860097n + BigInt(index)), seq: String(index + 1), senderId, sessionId: senderId,
    content: tracker.payload(`load-test-s1-${index}`), recalled: false, type: 'text'
  } }
}

function complete(tracker, index = 0) {
  tracker.accept(acknowledgement(index), senderId, index * 100 + 20, 's1')
  const receipt = tracker.accept(delivery(tracker, index), receiverId, index * 100 + 30, 's1')
  tracker.receiptSent(receipt.clientId)
}

function database(tracker) {
  const records = tracker.expected()
  const messages = records.map(record => ({ clientId: record.clientId, messageId: record.messageId,
    senderId: record.senderId, receiverId: record.receiverId, sequence: record.sequence }))
  const tasks = records.flatMap(record => [
    { clientId: record.clientId, messageId: record.messageId, targetUserId: record.senderId, status: 'PUBLISHED', bodyPresent: 0, revision: 0, attempts: 1 },
    { clientId: record.clientId, messageId: record.messageId, targetUserId: record.receiverId, status: 'DELIVERED', bodyPresent: 1, revision: 0, attempts: 1 }
  ])
  const counters = [{ key: `private:${senderId}:${receiverId}`, sequence: String(records.length) }]
  return { expected: records, messages, tasks, counters }
}

const verify = state => verifyDatabase(state.expected, state.messages, state.tasks, state.counters)

test('default workload is bounded and keeps each sender below the application rate limit', () => {
  const options = loadOptions(environment)
  assert.deepEqual(options.stagePairs, [4, 8, 16])
  assert.equal(options.ratePerPair, 5)
  assert.equal(options.seconds, 10)
  assert.equal(options.payloadBytes, 128)
})

test('requires an isolated local database and an explicit non-default Redis port', () => {
  for (const overrides of [
    { SCHAT_LOAD_MYSQL_URL: 'jdbc:mysql://db.example.test/schat_load' },
    { SCHAT_LOAD_MYSQL_URL: 'jdbc:mysql://127.0.0.1/schat' },
    { SCHAT_LOAD_MYSQL_URL: 'mysql://127.0.0.1/schat_load' },
    { SCHAT_LOAD_MYSQL_URL: 'jdbc:mysql://user:password@127.0.0.1/schat_load' },
    { SCHAT_LOAD_MYSQL_URL: 'jdbc:mysql://127.0.0.1/schat_load#fragment' },
    { SCHAT_LOAD_REDIS_PORT: undefined }, { SCHAT_LOAD_REDIS_PORT: '6379' }, { SCHAT_LOAD_REDIS_PORT: '0' }
  ]) assert.throws(() => loadOptions({ ...environment, ...overrides }))
})

test('rejects excessive, malformed, non-increasing or unbounded workload settings', () => {
  for (const overrides of [
    { SCHAT_LOAD_PAIRS: '4,4' }, { SCHAT_LOAD_PAIRS: '8,4' }, { SCHAT_LOAD_PAIRS: '65' },
    { SCHAT_LOAD_PAIRS: '1,2,3,4,5' }, { SCHAT_LOAD_PAIRS: '' }, { SCHAT_LOAD_RATE: '9' },
    { SCHAT_LOAD_SECONDS: '61' }, { SCHAT_LOAD_SECONDS: '2.5' }, { SCHAT_LOAD_PAYLOAD_BYTES: '4097' },
    { SCHAT_LOAD_MAX_IN_FLIGHT: '10001' }, { SCHAT_LOAD_DRAIN_SECONDS: '121' },
    { SCHAT_LOAD_PAIRS: '16,32,48,64', SCHAT_LOAD_SECONDS: '60', SCHAT_LOAD_RATE: '8' }
  ]) assert.throws(() => loadOptions({ ...environment, ...overrides }))
})

test('percentiles use nearest rank and never fabricate latency for empty samples', () => {
  const values = Array.from({ length: 100 }, (_, index) => 100 - index)
  assert.deepEqual(distribution(values), { count: 100, mean: 50.5, p50: 50, p95: 95, p99: 99, max: 100 })
  assert.equal(values[0], 100)
  assert.deepEqual(distribution([]), { count: 0, mean: null, p50: null, p95: null, p99: null, max: null })
  for (const values of [[-1], [NaN], [Infinity]]) assert.throws(() => distribution(values))
})

test('the first aborted offer retains the actual lag even though it is absent from sent-message samples', () => {
  assert.equal(generatorLagAbort(200, 100, 100, 33, 200), null)
  assert.deepEqual(generatorLagAbort(201, 100, 100, 33, 200), { lagMs: 101, offeredIndex: 33, remainingOffers: 167 })
  assert.throws(() => generatorLagAbort(NaN, 100, 100, 33, 200))
  const tracker = fixture()
  complete(tracker)
  tracker.unsent('s1', 167, 'GENERATOR_LAG_ABORT')
  const verdict = stageVerdict(tracker.summary('s1'), verify(database(tracker)), loadOptions(environment), 168)
  assert(verdict.reasons.includes('GENERATOR_LAG_TOO_HIGH'))
  assert(!verdict.reasons.includes('CLIENT_OR_SERVER_ERRORS'))
})

test('delivery can arrive before sender ACK without losing correlation or receipt requirements', () => {
  const tracker = fixture()
  const receipt = tracker.accept(delivery(tracker), receiverId, 20, 's1')
  assert.equal(tracker.pending, 1)
  tracker.receiptSent(receipt.clientId)
  assert.equal(tracker.pending, 1)
  tracker.accept(acknowledgement(), senderId, 30, 's1')
  assert.equal(tracker.pending, 0)
  const metrics = tracker.summary('s1')
  assert.equal(metrics.sendAckMs.p95, 20)
  assert.equal(metrics.receiveMs.p95, 10)
  assert.equal(metrics.scheduledToReceiveMs.p95, 15)
  assert.equal(metrics.generatorLagMs.p95, 5)
})

test('duplicate deliveries are acknowledged again but never increase unique throughput or change first latency', () => {
  const tracker = fixture()
  complete(tracker)
  const repeated = tracker.accept(delivery(tracker), receiverId, 100, 's1')
  tracker.receiptSent(repeated.clientId)
  tracker.accept(acknowledgement(), senderId, 120, 's1')
  const metrics = tracker.summary('s1')
  assert.equal(metrics.uniqueReceived, 1)
  assert.equal(metrics.senderAcknowledged, 1)
  assert.equal(metrics.duplicateDeliveries, 1)
  assert.equal(metrics.duplicateAcks, 1)
  assert.equal(metrics.receiptFrames, 2)
  assert.equal(metrics.receiveMs.p95, 20)
  assert.equal(tracker.pending, 0)
})

test('rejects numeric/unsafe IDs, inconsistent sequences and invalid timing', () => {
  for (const mutate of [
    packet => { packet.data.messageId = 2111849055417860097 },
    packet => { packet.data.seq = 1 },
    packet => { packet.data.messageId = '9223372036854775808' }
  ]) {
    const tracker = fixture()
    const packet = acknowledgement()
    mutate(packet)
    tracker.accept(packet, senderId, 30, 's1')
    assert.equal(tracker.summary('s1').senderAcknowledged, 0)
  }
  const tracker = fixture()
  tracker.accept(acknowledgement(), senderId, NaN, 's1')
  tracker.accept(acknowledgement(), senderId, 9, 's1')
  tracker.accept(acknowledgement(), senderId, 20, 's1')
  const inconsistent = delivery(tracker)
  inconsistent.data.seq = '2'
  assert.equal(tracker.accept(inconsistent, receiverId, 30, 's1'), null)
  assert.equal(tracker.summary('s1').uniqueReceived, 0)
})

test('wrong receiver, sender, session, content or revision cannot generate a false recipient ACK', () => {
  for (const mutate of [
    packet => { packet.data.senderId = receiverId }, packet => { packet.data.sessionId = receiverId },
    packet => { packet.data.content += 'changed' }, packet => { packet.deliveryRevision = 1 },
    packet => { packet.data.recalled = true }, packet => { packet.data.type = 'group_text' }
  ]) {
    const tracker = fixture()
    const packet = delivery(tracker)
    mutate(packet)
    assert.equal(tracker.accept(packet, receiverId, 20, 's1'), null)
    assert.equal(tracker.summary('s1').uniqueReceived, 0)
  }
  const tracker = fixture()
  assert.equal(tracker.accept(delivery(tracker), senderId, 20, 's1'), null)
  assert.throws(() => tracker.receiptSent('load-test-s1-0'))
})

test('server errors, unknown packets and unsent offers stay visible without copying private error text', () => {
  const tracker = fixture()
  tracker.accept({ type: 'ERROR', data: { message: 'private server failure' } }, senderId, 20, 's1')
  tracker.accept({ type: 'ERROR', data: { message: '发送过于频繁' } }, senderId, 21, 's1')
  tracker.accept(acknowledgement(2), senderId, 22, 's1')
  tracker.unsent('s1', 4, 'GENERATOR_LAG_ABORT')
  const metrics = tracker.summary('s1')
  assert.equal(metrics.unsent, 4)
  assert.equal(metrics.issues.SERVER_ERROR, 1)
  assert.equal(metrics.issues.SERVER_RATE_LIMIT, 1)
  assert.equal(metrics.issues.UNKNOWN_MESSAGE, 1)
  assert(!JSON.stringify(metrics).includes('private server failure'))
})

test('load records remain unique and payload byte count is fixed', () => {
  const tracker = fixture()
  assert.equal(Buffer.byteLength(tracker.payload('load-test-s1-0')), 128)
  assert.throws(() => tracker.offer({ ...tracker.expected()[0] }))
  assert.throws(() => tracker.payload('invalid|id'))
  assert.throws(() => tracker.offer({ clientId: 'new', stage: 's1', senderId, receiverId: senderId, scheduledAt: 1, sentAt: 2 }))
})

test('database reconciliation proves each ACK maps to one row and two correct terminal tasks', () => {
  const tracker = fixture(2)
  complete(tracker, 0)
  complete(tracker, 1)
  const checked = verify(database(tracker))
  assert.equal(checked.valid, true)
  assert.equal(checked.verifiedMessages, 2)
  assert.equal(checked.deliveredRecipients, 2)
  assert.equal(checked.databaseTasks, 4)
})

test('a success ACK without committed rows never passes reconciliation', () => {
  const tracker = fixture()
  complete(tracker)
  const state = database(tracker)
  state.messages = []
  state.tasks = []
  assert.equal(verify(state).valid, false)
  assert.equal(verify(state).issues.MISSING_OR_DUPLICATE_MESSAGE, 1)
})

test('missing, duplicate, stale or unconfirmed delivery tasks fail reconciliation', () => {
  const tracker = fixture()
  complete(tracker)
  for (const mutate of [
    state => state.tasks.pop(), state => state.tasks.push({ ...state.tasks[1] }),
    state => { state.tasks[1].status = 'AWAITING_ACK' }, state => { state.tasks[1].revision = 1 },
    state => { state.tasks[0].bodyPresent = 1 }, state => { state.tasks[1].attempts = 0 },
    state => { state.tasks[1].messageId = '1' }, state => { state.messages[0].messageId = '1' }
  ]) {
    const state = database(tracker)
    mutate(state)
    assert.equal(verify(state).valid, false)
  }
})

test('extra messages and orphan tasks cannot disappear behind an inner join', () => {
  const tracker = fixture()
  complete(tracker)
  const state = database(tracker)
  state.messages.push({ ...state.messages[0], clientId: 'unexpected' })
  state.tasks.push({ ...state.tasks[0], clientId: null })
  const checked = verify(state)
  assert.equal(checked.valid, false)
  assert.equal(checked.issues.UNEXPECTED_DATABASE_MESSAGE, 1)
  assert.equal(checked.issues.UNEXPECTED_OR_ORPHAN_TASK, 1)
})

test('conversation sequence gaps, duplicate values and incorrect counters fail with bigint-safe comparison', () => {
  const tracker = fixture(2)
  complete(tracker, 0)
  complete(tracker, 1)
  for (const mutate of [
    state => { state.messages[1].sequence = '3' }, state => { state.messages[1].sequence = '1' },
    state => { state.counters[0].sequence = '3' }, state => { state.counters[0].key = 'private:wrong' },
    state => state.counters.push({ ...state.counters[0] })
  ]) {
    const state = database(tracker)
    mutate(state)
    assert.equal(verify(state).valid, false)
  }
})

test('a simulated receipt must actually be sent before client confirmation is counted', () => {
  const tracker = fixture()
  tracker.accept(acknowledgement(), senderId, 20, 's1')
  tracker.accept(delivery(tracker), receiverId, 30, 's1')
  assert.equal(tracker.pending, 1)
  assert.equal(verify(database(tracker)).issues.INCOMPLETE_CLIENT_CONFIRMATION, 1)
})

test('SLO gates reject missing samples, incomplete offered load, latency and generator bottlenecks', () => {
  const tracker = fixture()
  complete(tracker)
  const metrics = tracker.summary('s1')
  const options = loadOptions(environment)
  const checked = verify(database(tracker))
  assert.equal(stageVerdict(metrics, checked, options, 1).passed, true)
  assert.equal(stageVerdict(metrics, checked, options, 2).passed, false)
  for (const mutate of [
    value => { value.sendAckMs.p95 = null }, value => { value.receiveMs.p95 = 3000 },
    value => { value.generatorLagMs.max = 200 }, value => { value.pending = 1 },
    value => { value.issues.SERVER_RATE_LIMIT = 1 }, value => { value.duplicateResponses = 1 }
  ]) {
    const bad = structuredClone(metrics)
    mutate(bad)
    assert.equal(stageVerdict(bad, checked, options, 1).passed, false)
  }
  assert.equal(stageVerdict(metrics, { valid: false }, options, 1).passed, false)
})
