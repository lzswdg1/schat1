import assert from 'node:assert/strict'
import { test } from 'node:test'
import { LoadTracker, loadOptions, scheduledOffsetMs, stageVerdict, verifyDatabase } from '../lib/load-metrics.mjs'

const environment = { SCHAT_LOAD_MYSQL_URL: 'jdbc:mysql://127.0.0.1:33671/schat_load', SCHAT_LOAD_REDIS_PORT: '33672' }
const groupId = '9007199254740997'
const users = ['9007199254740993', '9007199254740994', '9007199254740995']

function fixture(senders = 1) {
  const tracker = new LoadTracker(128)
  for (let index = 0; index < senders; index++) tracker.offer({ clientId: `group-test-${index}`, stage: 's1', groupId,
    senderId: users[index], recipientIds: users.filter(userId => userId !== users[index]), scheduledAt: 0, sentAt: 1 })
  return tracker
}

function senderAck(index = 0, sequence = String(index + 1)) {
  return { type: 'MSG_ACK', data: { clientMsgId: `group-test-${index}`, messageId: String(2111849055417860097n + BigInt(index)), seq: sequence, status: 'success' } }
}

function incoming(tracker, index = 0, sequence = String(index + 1)) {
  return { type: 'NEW_MESSAGE', deliveryId: '100', deliveryRevision: 0, data: {
    id: String(2111849055417860097n + BigInt(index)), seq: sequence, senderId: users[index], sessionId: `group_${groupId}`,
    type: 'text', recalled: false, content: tracker.payload(`group-test-${index}`)
  } }
}

function complete(tracker, index = 0, sequence = String(index + 1)) {
  tracker.accept(senderAck(index, sequence), users[index], 10, 's1')
  for (const userId of users.filter(userId => userId !== users[index])) {
    const receipt = tracker.accept(incoming(tracker, index, sequence), userId, 20 + users.indexOf(userId), 's1')
    tracker.receiptSent(receipt.clientId, receipt.recipientId)
  }
}

function database(tracker) {
  const expected = tracker.expected()
  return {
    expected,
    messages: expected.map(record => ({ clientId: record.clientId, messageId: record.messageId, senderId: record.senderId,
      groupId, receiverId: null, sequence: record.sequence })),
    tasks: expected.flatMap(record => users.map(userId => ({ clientId: record.clientId, messageId: record.messageId,
      targetUserId: userId, status: userId === record.senderId ? 'PUBLISHED' : 'DELIVERED',
      bodyPresent: userId === record.senderId ? 0 : 1, revision: 0, attempts: 1 }))),
    counters: [{ key: `group:${groupId}`, sequence: String(expected.length) }],
    memberships: users.map(userId => ({ groupId, userId }))
  }
}

const verify = state => verifyDatabase(state.expected, state.messages, state.tasks, state.counters, state.memberships)

test('fanout plan distinguishes logical messages, deliveries and tasks including warmup', () => {
  const options = loadOptions({ ...environment, SCHAT_LOAD_SCENARIO: 'group-fanout' })
  assert.equal(options.ratePerPair, 2)
  assert.deepEqual(options.stages.map(stage => stage.users), [4, 8, 16])
  assert.deepEqual(options.stages.map(stage => stage.senders), [1, 1, 1])
  assert.equal(options.stages.reduce((sum, stage) => sum + stage.projectedTasks, 0), 588)
  assert.equal(options.stages.reduce((sum, stage) => sum + (stage.planned + stage.warmupMessages) * stage.recipientsPerMessage, 0), 525)
})

test('hotspot plan synchronizes multiple senders while retaining each user rate limit', () => {
  const options = loadOptions({ ...environment, SCHAT_LOAD_SCENARIO: 'group-hotspot' })
  assert.deepEqual(options.stages.map(stage => stage.senders), [2, 4, 8])
  assert(options.stages.every(stage => stage.users === 8))
  assert.equal(options.stages.reduce((sum, stage) => sum + stage.projectedTasks, 0), 2248)
  const stage = options.stages[2]
  assert.deepEqual(Array.from({ length: 8 }, (_, index) => scheduledOffsetMs(stage, index, 2)), Array(8).fill(0))
  assert.equal(scheduledOffsetMs(stage, 8, 2), 500)
  assert.equal(scheduledOffsetMs(stage, 15, 2), 500)
  assert.equal(scheduledOffsetMs(stage, 16, 2), 1000)
  const privateStage = loadOptions(environment).stages[0]
  assert.equal(scheduledOffsetMs(privateStage, 1, 5), 50)
})

test('rejects mixed knobs, oversized fanout, too many senders and excessive per-user receipt rate', () => {
  for (const settings of [
    { SCHAT_LOAD_SCENARIO: 'unknown' },
    { SCHAT_LOAD_SCENARIO: 'group-fanout', SCHAT_LOAD_PAIRS: '4' },
    { SCHAT_LOAD_SCENARIO: 'group-fanout', SCHAT_LOAD_GROUP_MEMBERS: '1' },
    { SCHAT_LOAD_SCENARIO: 'group-fanout', SCHAT_LOAD_GROUP_MEMBERS: '64', SCHAT_LOAD_RATE: '8', SCHAT_LOAD_SECONDS: '60' },
    { SCHAT_LOAD_SCENARIO: 'group-hotspot', SCHAT_LOAD_GROUP_SIZE: '4' },
    { SCHAT_LOAD_SCENARIO: 'group-hotspot', SCHAT_LOAD_GROUP_SIZE: '64', SCHAT_LOAD_SENDERS: '32', SCHAT_LOAD_RATE: '8' },
    { SCHAT_LOAD_GROUP_SIZE: '8' }
  ]) assert.throws(() => loadOptions({ ...environment, ...settings }))
})

test('one recipient receipt cannot settle a whole group message', () => {
  const tracker = fixture()
  tracker.accept(senderAck(), users[0], 10, 's1')
  const first = tracker.accept(incoming(tracker), users[1], 20, 's1')
  tracker.receiptSent(first.clientId, first.recipientId)
  const metrics = tracker.summary('s1')
  assert.equal(tracker.pending, 1)
  assert.equal(metrics.uniqueReceived, 1)
  assert.equal(metrics.expectedRecipientDeliveries, 2)
  assert.equal(metrics.fullyReceivedMessages, 0)
  assert.equal(metrics.allRecipientsMs.count, 0)
  assert.throws(() => tracker.receiptSent(first.clientId))
  const second = tracker.accept(incoming(tracker), users[2], 40, 's1')
  tracker.receiptSent(second.clientId, second.recipientId)
  assert.equal(tracker.pending, 0)
  assert.equal(tracker.summary('s1').allRecipientsMs.p95, 39)
})

test('duplicates from one user do not hide an omitted group member', () => {
  const tracker = fixture()
  tracker.accept(senderAck(), users[0], 10, 's1')
  for (const now of [20, 30, 40]) {
    const receipt = tracker.accept(incoming(tracker), users[1], now, 's1')
    tracker.receiptSent(receipt.clientId, receipt.recipientId)
  }
  const metrics = tracker.summary('s1')
  assert.equal(metrics.uniqueReceived, 1)
  assert.equal(metrics.duplicateDeliveries, 2)
  assert.equal(metrics.receiptFrames, 3)
  assert.equal(metrics.pending, 1)
  assert.equal(stageVerdict(metrics, verify(database(tracker)), loadOptions(environment), 1, 2).passed, false)
})

test('nonmember, sender echo, wrong group or different sequence cannot create a receipt', () => {
  for (const userId of [users[0], '9007199254740996']) {
    const tracker = fixture()
    assert.equal(tracker.accept(incoming(tracker), userId, 10, 's1'), null)
    assert.equal(tracker.summary('s1').uniqueReceived, 0)
  }
  const tracker = fixture()
  const wrong = incoming(tracker)
  wrong.data.sessionId = 'group_1'
  assert.equal(tracker.accept(wrong, users[1], 10, 's1'), null)
  tracker.accept(senderAck(), users[0], 10, 's1')
  assert.equal(tracker.accept(incoming(tracker, 0, '2'), users[1], 20, 's1'), null)
})

test('concurrent writers share one contiguous group counter without assuming client offer order', () => {
  const tracker = fixture(2)
  complete(tracker, 0, '2')
  complete(tracker, 1, '1')
  const checked = verify(database(tracker))
  assert.equal(checked.valid, true)
  assert.equal(checked.verifiedMessages, 2)
  assert.equal(checked.deliveredRecipients, 4)
  assert.equal(checked.databaseTasks, 6)
  const metrics = tracker.summary('s1')
  assert.equal(metrics.sent, 2)
  assert.equal(metrics.uniqueReceived, 4)
  assert.equal(stageVerdict(metrics, checked, loadOptions(environment), 2, 4).passed, true)
})

test('group SQL reconciliation catches duplicated targets, missing receipts and wrong membership snapshots', () => {
  const tracker = fixture()
  complete(tracker)
  for (const mutate of [
    state => { state.tasks[2].targetUserId = users[1] },
    state => { state.tasks[2].status = 'AWAITING_ACK' },
    state => { state.tasks[0].bodyPresent = 1 },
    state => { state.messages[0].groupId = '1' },
    state => { state.counters[0].sequence = '2' },
    state => state.memberships.pop(),
    state => state.memberships.push({ groupId, userId: '9007199254740996' }),
    state => state.memberships.push({ ...state.memberships[0] })
  ]) {
    const state = database(tracker)
    mutate(state)
    assert.equal(verify(state).valid, false)
  }
})

test('a hidden slow recipient fails the all-recipient SLO even if other receipts dominate samples', () => {
  const tracker = fixture()
  complete(tracker)
  const metrics = tracker.summary('s1')
  metrics.receiveMs.p95 = 100
  metrics.allRecipientsMs.p95 = 3000
  const verdict = stageVerdict(metrics, verify(database(tracker)), loadOptions(environment), 1, 2)
  assert(verdict.reasons.includes('ALL_RECIPIENTS_P95_LIMIT'))
})
