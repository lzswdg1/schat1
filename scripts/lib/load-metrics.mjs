import assert from 'node:assert/strict'

function integer(value, fallback, minimum, maximum, label) {
  const text = value === undefined ? String(fallback) : value
  assert(typeof text === 'string' && /^\d+$/.test(text), `Invalid ${label}`)
  const result = Number(text)
  assert(Number.isSafeInteger(result) && result >= minimum && result <= maximum, `Invalid ${label}`)
  return result
}

export function loadOptions(environment) {
  const jdbcUrl = environment.SCHAT_LOAD_MYSQL_URL
  assert(typeof jdbcUrl === 'string' && jdbcUrl.startsWith('jdbc:mysql://'), 'Set SCHAT_LOAD_MYSQL_URL')
  const database = new URL(jdbcUrl.slice(5))
  assert(['127.0.0.1', 'localhost'].includes(database.hostname) && database.pathname === '/schat_load',
    'Only a local isolated schat_load database is allowed')
  assert(!database.username && !database.password && !database.hash, 'Do not embed credentials or fragments in the database URL')
  assert(environment.SCHAT_LOAD_REDIS_PORT, 'Set an explicitly isolated SCHAT_LOAD_REDIS_PORT')
  const redisPort = integer(environment.SCHAT_LOAD_REDIS_PORT, 0, 1, 65535, 'Redis port')
  assert(redisPort !== 6379, 'The default Redis port is forbidden for load tests')
  const scenario = environment.SCHAT_LOAD_SCENARIO ?? 'private'
  assert(['private', 'group-fanout', 'group-hotspot'].includes(scenario), 'Invalid load scenario')
  const setting = scenario === 'private' ? 'SCHAT_LOAD_PAIRS'
    : scenario === 'group-fanout' ? 'SCHAT_LOAD_GROUP_MEMBERS' : 'SCHAT_LOAD_SENDERS'
  for (const other of ['SCHAT_LOAD_PAIRS', 'SCHAT_LOAD_GROUP_MEMBERS', 'SCHAT_LOAD_SENDERS']) {
    assert(other === setting || environment[other] === undefined, `Do not combine ${other} with ${scenario}`)
  }
  assert(scenario === 'group-hotspot' || environment.SCHAT_LOAD_GROUP_SIZE === undefined, 'Group size applies only to group-hotspot')
  const pairText = environment[setting] ?? (scenario === 'group-hotspot' ? '2,4,8' : '4,8,16')
  assert(/^\d+(,\d+){0,3}$/.test(pairText), 'Specify one to four increasing pair counts')
  const stagePairs = pairText.split(',').map(value => integer(value, 0, scenario === 'group-fanout' ? 2 : 1, 64, 'stage size'))
  assert(stagePairs.every((value, index) => index === 0 || value > stagePairs[index - 1]), 'Pair counts must increase')
  const seconds = integer(environment.SCHAT_LOAD_SECONDS, 10, 2, 60, 'stage duration')
  const ratePerPair = integer(environment.SCHAT_LOAD_RATE, scenario === 'private' ? 5 : 2, 1, 8, 'messages per sender per second')
  const groupSize = scenario === 'group-hotspot' ? integer(environment.SCHAT_LOAD_GROUP_SIZE, 8, 2, 64, 'group size') : null
  const stages = stagePairs.map((size, index) => {
    const users = scenario === 'private' ? size * 2 : scenario === 'group-fanout' ? size : groupSize
    const senders = scenario === 'group-fanout' ? 1 : size
    assert(senders <= users, 'Sender count cannot exceed group membership')
    const recipientsPerMessage = scenario === 'private' ? 1 : users - 1
    const warmupMessages = scenario === 'private' ? size - (stagePairs[index - 1] ?? 0)
      : scenario === 'group-fanout' || index === 0 ? 1 : 0
    const planned = senders * seconds * ratePerPair
    if (scenario === 'group-hotspot') assert((senders === users ? senders - 1 : senders) * ratePerPair <= 150,
      'Projected per-user receipt rate must not exceed 150 per second')
    return { size, users, senders, scheduling: scenario === 'group-hotspot' ? 'synchronized-sender-rounds' : 'staggered',
      recipientsPerMessage, planned, plannedRecipientDeliveries: planned * recipientsPerMessage,
      warmupMessages, projectedTasks: (planned + warmupMessages) * (recipientsPerMessage + 1) }
  })
  assert(stages.reduce((sum, stage) => sum + stage.planned + stage.warmupMessages, 0) <= 25000,
    'The total planned workload including warmup must not exceed 25000 messages')
  if (scenario !== 'private') assert(stages.reduce((sum, stage) => sum + stage.projectedTasks, 0) <= 25000,
    'Group workload including warmup must not exceed 25000 projected Outbox tasks')
  return {
    jdbcUrl, database, redisPort, stagePairs, seconds, ratePerPair, scenario, stages, groupSize,
    payloadBytes: integer(environment.SCHAT_LOAD_PAYLOAD_BYTES, 128, 64, 4096, 'payload bytes'),
    drainSeconds: integer(environment.SCHAT_LOAD_DRAIN_SECONDS, 30, 5, 120, 'drain duration'),
    maxInFlight: integer(environment.SCHAT_LOAD_MAX_IN_FLIGHT, 2000, 100, 10000, 'in-flight bound'),
    maxGeneratorLagMs: integer(environment.SCHAT_LOAD_MAX_LAG_MS, 100, 10, 1000, 'generator lag bound'),
    ackP95LimitMs: integer(environment.SCHAT_LOAD_ACK_P95_MS, 1000, 1, 60000, 'ACK p95 limit'),
    receiveP95LimitMs: integer(environment.SCHAT_LOAD_RECEIVE_P95_MS, 2000, 1, 60000, 'receive p95 limit')
  }
}

export function distribution(values) {
  assert(values.every(value => Number.isFinite(value) && value >= 0), 'Latency samples must be finite and nonnegative')
  const sorted = [...values].sort((first, second) => first - second)
  const percentile = fraction => sorted.length ? sorted[Math.ceil(sorted.length * fraction) - 1] : null
  return {
    count: sorted.length,
    mean: sorted.length ? sorted.reduce((sum, value) => sum + value, 0) / sorted.length : null,
    p50: percentile(0.5), p95: percentile(0.95), p99: percentile(0.99), max: sorted.at(-1) ?? null
  }
}

export function generatorLagAbort(now, scheduledAt, limitMs, index, planned) {
  assert([now, scheduledAt, limitMs].every(Number.isFinite) && limitMs > 0
    && Number.isInteger(index) && index >= 0 && Number.isInteger(planned) && planned > index, 'Invalid pacing decision')
  const lagMs = Math.max(0, now - scheduledAt)
  return lagMs > limitMs ? { lagMs, offeredIndex: index, remainingOffers: planned - index } : null
}

export function scheduledOffsetMs(plan, index, ratePerSender) {
  assert(Number.isInteger(index) && index >= 0 && index < plan.planned && plan.senders > 0 && ratePerSender > 0, 'Invalid schedule')
  return plan.scheduling === 'synchronized-sender-rounds'
    ? Math.floor(index / plan.senders) * 1000 / ratePerSender : index * 1000 / (plan.senders * ratePerSender)
}

function identifier(value) {
  return typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
}

export class LoadTracker {
  #records = new Map()
  #issues = new Map()
  #unsent = new Map()
  #pending = 0
  #payloadBytes

  constructor(payloadBytes) {
    this.#payloadBytes = payloadBytes
  }

  payload(clientId) {
    assert(/^[a-zA-Z0-9-]{1,60}$/.test(clientId) && clientId.length + 1 <= this.#payloadBytes, 'Invalid load message ID')
    return `${clientId}|${'x'.repeat(this.#payloadBytes - clientId.length - 1)}`
  }

  offer(record) {
    assert(!this.#records.has(record.clientId), 'Load IDs must be unique')
    const recipientIds = record.groupId === undefined ? [record.receiverId] : record.recipientIds
    assert(identifier(record.senderId) && (record.groupId === undefined || identifier(record.groupId)), 'Invalid sender or group')
    assert(Array.isArray(recipientIds) && recipientIds.length > 0 && recipientIds.length <= 63
      && recipientIds.every(userId => identifier(userId) && userId !== record.senderId)
      && new Set(recipientIds).size === recipientIds.length, 'Invalid load recipients')
    assert(Number.isFinite(record.scheduledAt) && Number.isFinite(record.sentAt) && record.sentAt >= record.scheduledAt, 'Invalid send timing')
    this.payload(record.clientId)
    this.#records.set(record.clientId, { ...record, recipientIds: [...recipientIds],
      recipients: new Map(recipientIds.map(userId => [userId, { receivedAt: null, receiptCount: 0, duplicateDeliveries: 0 }])),
      ackedAt: null, receivedAt: null, duplicateAcks: 0, duplicateResponses: 0, messageId: null, sequence: null, settled: false })
    this.#pending++
  }

  issue(stage, code) {
    if (!this.#issues.has(stage)) this.#issues.set(stage, new Map())
    const issues = this.#issues.get(stage)
    issues.set(code, (issues.get(code) || 0) + 1)
  }

  unsent(stage, count, code) {
    this.#unsent.set(stage, (this.#unsent.get(stage) || 0) + count)
    this.issue(stage, code)
  }

  accept(packet, userId, now, phase) {
    if (packet.type === 'ERROR') {
      this.issue(phase, String(packet.data?.message).includes('频繁') ? 'SERVER_RATE_LIMIT' : 'SERVER_ERROR')
      return null
    }
    if (!['MSG_ACK', 'NEW_MESSAGE'].includes(packet.type)) return null
    const data = packet.data || {}
    const clientId = packet.type === 'MSG_ACK' ? data.clientMsgId : String(data.content).split('|')[0]
    const record = this.#records.get(clientId)
    const stage = record?.stage ?? phase
    if (!record) {
      this.issue(stage, 'UNKNOWN_MESSAGE')
      return null
    }
    const messageId = packet.type === 'MSG_ACK' ? data.messageId : data.id
    if (!identifier(messageId) || !identifier(data.seq) || !Number.isFinite(now) || now < record.sentAt
      || (record.messageId !== null && record.messageId !== messageId)
      || (record.sequence !== null && record.sequence !== data.seq)) {
      this.issue(stage, 'INVALID_ID_SEQUENCE_OR_TIMING')
      return null
    }
    if (packet.type === 'MSG_ACK') {
      if (userId !== record.senderId || !['success', 'duplicate'].includes(data.status)) {
        this.issue(stage, 'INVALID_SENDER_ACK')
        return null
      }
      if (record.ackedAt === null) record.ackedAt = now
      else record.duplicateAcks++
      if (data.status === 'duplicate') record.duplicateResponses++
    } else {
      const recipient = record.recipients.get(userId)
      const sessionId = record.groupId === undefined ? record.senderId : `group_${record.groupId}`
      if (!recipient || data.senderId !== record.senderId || data.sessionId !== sessionId
        || data.type !== 'text' || data.content !== this.payload(clientId)
        || data.recalled !== false || (packet.deliveryRevision ?? 0) !== 0
        || (packet.deliveryId !== undefined && !identifier(packet.deliveryId))) {
        this.issue(stage, 'INVALID_RECIPIENT_DELIVERY')
        return null
      }
      if (record.receivedAt === null) record.receivedAt = now
      if (recipient.receivedAt === null) recipient.receivedAt = now
      else recipient.duplicateDeliveries++
    }
    record.messageId = messageId
    record.sequence = data.seq
    this.#settle(record)
    return packet.type === 'NEW_MESSAGE'
      ? { clientId, recipientId: userId, frame: { msgType: 'DELIVERY_ACK', messageId, deliveryRevision: 0 } } : null
  }

  receiptSent(clientId, userId) {
    const record = this.#records.get(clientId)
    const recipient = record?.recipients.get(userId ?? record.receiverId)
    assert(recipient && recipient.receivedAt !== null, 'Only validated recipient deliveries can be acknowledged')
    recipient.receiptCount++
    this.#settle(record)
  }

  #settle(record) {
    if (!record.settled && record.ackedAt !== null && [...record.recipients.values()]
      .every(recipient => recipient.receivedAt !== null && recipient.receiptCount > 0)) {
      record.settled = true
      this.#pending--
    }
  }

  get pending() { return this.#pending }

  expected(stage) {
    return [...this.#records.values()].filter(record => stage === undefined || record.stage === stage).map(record => {
      const { recipients, ...metadata } = record
      return { ...metadata, recipientIds: [...record.recipientIds],
        observations: [...recipients].map(([userId, observation]) => ({ userId, ...observation })) }
    })
  }

  summary(stage) {
    const records = this.expected(stage)
    const acknowledged = records.filter(record => record.ackedAt !== null)
    const observations = records.flatMap(record => record.observations.map(observation => ({ ...observation, record })))
    const received = observations.filter(observation => observation.receivedAt !== null)
    const fullyReceived = records.filter(record => record.observations.every(observation => observation.receivedAt !== null))
    return {
      sent: records.length, senderAcknowledged: acknowledged.length, uniqueReceived: received.length,
      expectedRecipientDeliveries: observations.length, fullyReceivedMessages: fullyReceived.length,
      pending: records.filter(record => !record.settled).length,
      unsent: this.#unsent.get(stage) || 0, issues: Object.fromEntries(this.#issues.get(stage) || []),
      duplicateDeliveries: observations.reduce((sum, observation) => sum + observation.duplicateDeliveries, 0),
      duplicateAcks: records.reduce((sum, record) => sum + record.duplicateAcks, 0),
      duplicateResponses: records.reduce((sum, record) => sum + record.duplicateResponses, 0),
      receiptFrames: observations.reduce((sum, observation) => sum + observation.receiptCount, 0),
      generatorLagMs: distribution(records.map(record => record.sentAt - record.scheduledAt)),
      sendAckMs: distribution(acknowledged.map(record => record.ackedAt - record.sentAt)),
      receiveMs: distribution(received.map(observation => observation.receivedAt - observation.record.sentAt)),
      scheduledToReceiveMs: distribution(received.map(observation => observation.receivedAt - observation.record.scheduledAt)),
      allRecipientsMs: distribution(fullyReceived.map(record => Math.max(...record.observations.map(observation => observation.receivedAt)) - record.sentAt))
    }
  }
}

export function verifyDatabase(expected, messages, tasks, counters, memberships = []) {
  const issues = new Map()
  const fail = code => issues.set(code, (issues.get(code) || 0) + 1)
  const expectedById = new Map(expected.map(record => [record.clientId, record]))
  const byClient = new Map()
  const tasksByClient = new Map()
  for (const message of messages) {
    if (!byClient.has(message.clientId)) byClient.set(message.clientId, [])
    byClient.get(message.clientId).push(message)
    if (!expectedById.has(message.clientId)) fail('UNEXPECTED_DATABASE_MESSAGE')
  }
  for (const task of tasks) {
    if (!tasksByClient.has(task.clientId)) tasksByClient.set(task.clientId, [])
    tasksByClient.get(task.clientId).push(task)
    if (!expectedById.has(task.clientId)) fail('UNEXPECTED_OR_ORPHAN_TASK')
  }
  let verifiedMessages = 0
  let deliveredRecipients = 0
  for (const record of expected) {
    if (!record.settled) fail('INCOMPLETE_CLIENT_CONFIRMATION')
    const rows = byClient.get(record.clientId) || []
    if (rows.length !== 1) { fail('MISSING_OR_DUPLICATE_MESSAGE'); continue }
    const message = rows[0]
    if (message.messageId !== record.messageId || message.sequence !== record.sequence || message.senderId !== record.senderId
      || (message.groupId ?? null) !== (record.groupId ?? null)
      || (record.groupId === undefined && message.receiverId !== record.receiverId)) fail('MESSAGE_ACK_MISMATCH')
    else verifiedMessages++
    const entries = tasksByClient.get(record.clientId) || []
    const recipientIds = record.recipientIds ?? [record.receiverId]
    if (entries.length !== recipientIds.length + 1) fail('INCORRECT_TASK_COUNT')
    const senders = entries.filter(task => task.targetUserId === record.senderId && task.bodyPresent === 0
      && task.status === 'PUBLISHED' && task.revision === 0)
    for (const userId of recipientIds) {
      const recipients = entries.filter(task => task.targetUserId === userId && task.bodyPresent === 1
        && task.status === 'DELIVERED' && task.revision === 0)
      if (recipients.length === 1) deliveredRecipients++
      else fail('RECIPIENT_NOT_DURABLY_CONFIRMED')
    }
    if (senders.length !== 1) fail('SENDER_CACHE_NOT_COMPLETED')
    if (entries.some(task => task.messageId !== message.messageId || task.attempts < 1 || task.attempts > 12)) fail('INVALID_TASK_METADATA')
  }
  const conversations = new Map()
  for (const message of messages) {
    if (![message.senderId, message.groupId ?? message.receiverId, message.sequence].every(identifier)) { fail('INVALID_DATABASE_IDENTIFIER'); continue }
    const users = [message.senderId, message.receiverId].filter(Boolean).sort((first, second) => BigInt(first) < BigInt(second) ? -1 : 1)
    const key = message.groupId === undefined || message.groupId === null ? `private:${users.join(':')}` : `group:${message.groupId}`
    if (!conversations.has(key)) conversations.set(key, [])
    conversations.get(key).push(BigInt(message.sequence))
  }
  const counterMap = new Map(counters.map(counter => [counter.key, counter.sequence]))
  if (counterMap.size !== conversations.size || counterMap.size !== counters.length) fail('COUNTER_COUNT_MISMATCH')
  for (const [key, sequences] of conversations) {
    sequences.sort((first, second) => first < second ? -1 : first > second ? 1 : 0)
    if (sequences.some((value, index) => value !== BigInt(index + 1)) || counterMap.get(key) !== String(sequences.length)) {
      fail('SEQUENCE_GAP_DUPLICATE_OR_COUNTER_MISMATCH')
    }
  }
  const expectedMemberships = new Map()
  for (const record of expected.filter(record => record.groupId !== undefined)) {
    const users = new Set([record.senderId, ...record.recipientIds])
    const previous = expectedMemberships.get(record.groupId)
    if (previous && (previous.size !== users.size || [...users].some(userId => !previous.has(userId)))) fail('INCONSISTENT_GROUP_SNAPSHOT')
    expectedMemberships.set(record.groupId, users)
  }
  const actualMemberships = new Map()
  for (const member of memberships) {
    if (!actualMemberships.has(member.groupId)) actualMemberships.set(member.groupId, new Set())
    const users = actualMemberships.get(member.groupId)
    if (users.has(member.userId) || !expectedMemberships.get(member.groupId)?.has(member.userId)) fail('UNEXPECTED_OR_DUPLICATE_GROUP_MEMBER')
    users.add(member.userId)
  }
  for (const [groupId, users] of expectedMemberships) {
    if (users.size !== actualMemberships.get(groupId)?.size || [...users].some(userId => !actualMemberships.get(groupId)?.has(userId))) {
      fail('GROUP_MEMBERSHIP_MISMATCH')
    }
  }
  return { valid: issues.size === 0, expectedMessages: expected.length, databaseMessages: messages.length,
    databaseTasks: tasks.length, verifiedMessages, deliveredRecipients, issues: Object.fromEntries(issues) }
}

export function stageVerdict(metrics, database, options, planned, plannedDeliveries = planned) {
  const reasons = []
  if (metrics.sent !== planned || metrics.unsent > 0) reasons.push('GENERATOR_DID_NOT_SEND_ALL_PLANNED_MESSAGES')
  if (Object.keys(metrics.issues).some(code => code !== 'GENERATOR_LAG_ABORT')) reasons.push('CLIENT_OR_SERVER_ERRORS')
  if (metrics.senderAcknowledged !== planned || metrics.uniqueReceived !== plannedDeliveries || metrics.pending > 0) reasons.push('INCOMPLETE_CLIENT_RESULTS')
  if (metrics.duplicateResponses > 0) reasons.push('UNEXPECTED_DUPLICATE_SEND_ACK')
  if (!database.valid) reasons.push('DATABASE_RECONCILIATION_FAILED')
  if (metrics.issues.GENERATOR_LAG_ABORT || metrics.generatorLagMs.max === null
    || metrics.generatorLagMs.max > options.maxGeneratorLagMs) reasons.push('GENERATOR_LAG_TOO_HIGH')
  if (metrics.sendAckMs.p95 === null || metrics.sendAckMs.p95 > options.ackP95LimitMs) reasons.push('ACK_P95_LIMIT')
  if (metrics.receiveMs.p95 === null || metrics.receiveMs.p95 > options.receiveP95LimitMs) reasons.push('RECEIVE_P95_LIMIT')
  if (metrics.allRecipientsMs && (metrics.allRecipientsMs.p95 === null
    || metrics.allRecipientsMs.p95 > options.receiveP95LimitMs)) reasons.push('ALL_RECIPIENTS_P95_LIMIT')
  return { passed: reasons.length === 0, reasons }
}
