import assert from 'node:assert/strict'
import { execFile, spawn } from 'node:child_process'
import { closeSync, mkdtempSync, openSync, statSync, writeFileSync } from 'node:fs'
import { cpus, loadavg, platform, release, tmpdir, totalmem } from 'node:os'
import { join, resolve } from 'node:path'
import { createServer } from 'node:net'
import { randomBytes } from 'node:crypto'
import { monitorEventLoopDelay, performance } from 'node:perf_hooks'
import { promisify } from 'node:util'
import { generatorLagAbort, LoadTracker, loadOptions, scheduledOffsetMs, stageVerdict, verifyDatabase } from './lib/load-metrics.mjs'

if (process.argv.includes('--help')) {
  console.log('Local-only delivery baseline: set SCHAT_LOAD_MYSQL_URL (empty schat_load) and SCHAT_LOAD_REDIS_PORT (empty isolated Redis, not 6379).')
  console.log('Optional: SCHAT_LOAD_PAIRS=4,8,16 SCHAT_LOAD_SECONDS=10 SCHAT_LOAD_RATE=5 SCHAT_LOAD_PAYLOAD_BYTES=128')
  console.log('Group modes: SCHAT_LOAD_SCENARIO=group-fanout (SCHAT_LOAD_GROUP_MEMBERS=4,8,16) or group-hotspot (SCHAT_LOAD_SENDERS=2,4,8 SCHAT_LOAD_GROUP_SIZE=8); default rate=2.')
  console.log('Keeps production rate limits; starts auth + two chat JVMs on 33920–33922. Never resets databases or flushes Redis.')
  process.exit(0)
}

const options = loadOptions(process.env)
const root = resolve(import.meta.dirname, '..')
const output = mkdtempSync(join(tmpdir(), 'schat-load-'))
const execute = promisify(execFile)
const mysql = process.env.SCHAT_LOAD_MYSQL_BIN || 'mysql'
const redis = process.env.SCHAT_LOAD_REDIS_BIN || 'redis-cli'
const password = process.env.SCHAT_LOAD_MYSQL_PASSWORD || 'schat-test-only'
const jwtSecret = randomBytes(48).toString('hex')
const internalSecret = randomBytes(48).toString('hex')
const opsSecret = randomBytes(48).toString('hex')
const runId = randomBytes(6).toString('hex')
const tracker = new LoadTracker(options.payloadBytes)
const children = []
const peers = []
const pairs = []
const stageResults = []
const receivedFrameCounts = new Map()
let phase = 'bootstrap'
let shuttingDown = false
let interrupted = false
let nextAuthAt = 0
process.once('SIGINT', () => { interrupted = true })
process.once('SIGTERM', () => { interrupted = true })

const report = {
  schemaVersion: 2, runId, startedAt: new Date().toISOString(), passed: false,
  scope: `local two-chat-JVM ${options.scenario} baseline; not a production capacity limit`,
  configuration: {
    scenario: options.scenario, stagePlan: options.stages,
    stagePairs: options.scenario === 'private' ? options.stagePairs : undefined,
    secondsPerStage: options.seconds, messagesPerSenderPerSecond: options.ratePerPair,
    payloadBytes: options.payloadBytes, drainSeconds: options.drainSeconds, maxInFlight: options.maxInFlight,
    maxGeneratorLagMs: options.maxGeneratorLagMs, ackP95LimitMs: options.ackP95LimitMs,
    receiveP95LimitMs: options.receiveP95LimitMs, heapPerJvm: '-Xms64m -Xmx256m',
    appPorts: [33920, 33921, 33922], rateLimitsUnchanged: true, gatewayUsed: false,
    authRequestMinIntervalMs: 1100,
    receiverPersistence: 'in-memory validation then immediate DELIVERY_ACK; no browser/IndexedDB/encryption'
  },
  environment: { node: process.version, platform: platform(), osRelease: release(), cpu: cpus()[0]?.model,
    logicalCpus: cpus().length, totalMemoryBytes: totalmem(), loadAverageAtStart: loadavg() },
  stages: stageResults
}

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

async function until(description, predicate, timeout = 30000) {
  const deadline = performance.now() + timeout
  while (performance.now() < deadline) {
    if (interrupted) throw new Error('Load run interrupted')
    const result = await predicate()
    if (result) return result
    await delay(50)
  }
  throw new Error(`Timed out: ${description}`)
}

async function sql(statement) {
  const result = await execute(mysql, ['--no-defaults', '--protocol=TCP', '-h', options.database.hostname,
    '-P', options.database.port || '3306', '-u', 'root', '--batch', '--skip-column-names', 'schat_load', '-e', statement], {
    env: { ...process.env, MYSQL_PWD: password }, encoding: 'utf8', timeout: 10000, maxBuffer: 16 * 1024 * 1024
  })
  return result.stdout.trim()
}

async function sqlRows(statement) {
  const text = await sql(statement)
  return text ? text.split('\n').map(row => JSON.parse(row)) : []
}

function launch(module, port, instance) {
  const jar = join(root, module, 'target', `${module}-0.0.1-SNAPSHOT.jar`)
  const descriptor = openSync(join(output, `${instance}.log`), 'a')
  const child = spawn('java', ['-Xms64m', '-Xmx256m', '-jar', jar,
    `--server.port=${port}`, '--server.address=127.0.0.1', '--spring.config.import=',
    `--spring.datasource.url=${options.jdbcUrl}`, '--spring.datasource.username=root', `--spring.datasource.password=${password}`,
    '--spring.cloud.nacos.discovery.enabled=false', '--spring.cloud.discovery.enabled=false',
    '--spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration',
    '--spring.data.redis.host=127.0.0.1', `--spring.data.redis.port=${options.redisPort}`, '--spring.data.redis.password=',
    `--schat.instance.id=${instance}`, '--schat.shutdown.wait-seconds=0',
    '--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl'], {
    cwd: root, stdio: ['ignore', descriptor, descriptor], env: { ...process.env,
      SCHAT_JWT_SECRET: jwtSecret, SCHAT_INTERNAL_SECRET: internalSecret, MYSQL_PASSWORD: password, REDIS_PASSWORD: '',
      SCHAT_DELIVERY_OPS_ENABLED: String(module === 's-server' && port === 33921), SCHAT_DELIVERY_OPS_SECRET: opsSecret,
      SCHAT_DELIVERY_OPS_AUTO_CLEANUP_ENABLED: 'false', SCHAT_DELIVERY_ALERT_ENABLED: 'false'
    }
  })
  closeSync(descriptor)
  child.instance = instance
  child.once('error', () => { child.spawnFailed = true })
  children.push(child)
  return child
}

async function ready(child, port) {
  await until(`service ${child.instance} startup`, async () => {
    if (child.spawnFailed || child.exitCode !== null || child.signalCode !== null) throw new Error(`Service ${child.instance} exited; inspect its log`)
    try {
      return (await fetch(`http://127.0.0.1:${port}/v3/api-docs`, { signal: AbortSignal.timeout(2000) })).ok
    } catch { return false }
  }, 90000)
}

async function api(path, body) {
  while (performance.now() < nextAuthAt) {
    if (interrupted) throw new Error('Load run interrupted')
    await delay(Math.max(1, nextAuthAt - performance.now()))
  }
  nextAuthAt = performance.now() + 1100
  const response = await fetch(`http://127.0.0.1:33920${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: AbortSignal.timeout(10000)
  })
  const result = await response.json()
  assert(response.ok && result.code === 200, `Authentication setup failed: ${path}`)
  return result.data
}

function transmit(peer, frame) {
  if (peer.socket.readyState !== WebSocket.OPEN || peer.socket.bufferedAmount > 1048576) return false
  try { peer.socket.send(JSON.stringify(frame)); return true } catch { return false }
}

async function connect(user, port) {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/ws/chat/${user.user.id}?token=${encodeURIComponent(user.token)}`)
  const peer = { socket, userId: String(user.user.id), ready: false, readinessProbes: 0 }
  peers.push(peer)
  socket.addEventListener('message', event => {
    try {
      const packet = JSON.parse(event.data)
      const kind = ['MSG_ACK', 'NEW_MESSAGE', 'HEARTBEAT_ACK', 'ERROR'].includes(packet.type) ? packet.type : 'OTHER'
      receivedFrameCounts.set(kind, (receivedFrameCounts.get(kind) || 0) + 1)
      if (packet.type === 'HEARTBEAT_ACK') peer.ready = true
      const receipt = tracker.accept(packet, peer.userId, performance.now(), phase)
      if (receipt) {
        if (transmit(peer, receipt.frame)) tracker.receiptSent(receipt.clientId, receipt.recipientId)
        else tracker.issue(phase, 'CLIENT_RECEIPT_BACKPRESSURE')
      }
    } catch { tracker.issue(phase, 'INVALID_WEBSOCKET_FRAME') }
  })
  socket.addEventListener('error', () => { if (!shuttingDown) tracker.issue(phase, 'SOCKET_ERROR') })
  socket.addEventListener('close', () => { if (!shuttingDown) tracker.issue(phase, 'UNEXPECTED_SOCKET_CLOSE') })
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('WebSocket open timed out')), 10000)
    socket.addEventListener('open', () => { clearTimeout(timeout); resolve() }, { once: true })
    socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('WebSocket open failed')) }, { once: true })
  })
  const probe = () => {
    peer.readinessProbes++
    transmit(peer, { type: 'HEARTBEAT' })
  }
  const readinessTimer = setInterval(probe, 1000)
  try {
    probe()
    await until('authenticated WebSocket heartbeat', () => peer.ready)
  } finally { clearInterval(readinessTimer) }
  peer.heartbeat = setInterval(() => transmit(peer, { type: 'HEARTBEAT' }), 15000)
  return peer
}

function send(pair, stage, index, scheduledAt) {
  if (tracker.pending >= options.maxInFlight) { tracker.unsent(stage, 1, 'CLIENT_IN_FLIGHT_BOUND'); return }
  const clientId = `load-${runId}-${stage}-${index}`
  if (pair.sender.socket.readyState !== WebSocket.OPEN || pair.sender.socket.bufferedAmount > 1048576) {
    tracker.unsent(stage, 1, 'CLIENT_SEND_BACKPRESSURE')
    return
  }
  const address = pair.groupId === undefined ? { receiverId: pair.receiver.userId }
    : { groupId: pair.groupId, recipientIds: pair.recipientIds }
  tracker.offer({ clientId, stage, senderId: pair.sender.userId, ...address, scheduledAt, sentAt: performance.now() })
  const frame = pair.groupId === undefined ? { msgType: 'TEXT', toId: pair.receiver.userId }
    : { msgType: 'GROUP_TEXT', groupId: pair.groupId, toId: pair.groupId }
  if (!transmit(pair.sender, { ...frame, clientMsgId: clientId, content: tracker.payload(clientId) })) {
    tracker.issue(stage, 'CLIENT_SEND_FAILED')
  }
}

async function completedTasks() {
  const expected = tracker.expected().reduce((sum, record) => sum + record.recipientIds.length + 1, 0)
  const result = await sql("SELECT COUNT(*) FROM tb_message_outbox WHERE status IN ('DELIVERED','PUBLISHED')")
  return Number(result) === expected
}

async function reconcile() {
  const messages = await sqlRows(`SELECT JSON_OBJECT('clientId',client_msg_id,'messageId',CAST(message_id AS CHAR),
    'senderId',CAST(sender_id AS CHAR),'receiverId',CAST(receiver_id AS CHAR),'sequence',CAST(seq AS CHAR),'groupId',NULL) FROM tb_chat_message
    UNION ALL SELECT JSON_OBJECT('clientId',client_msg_id,'messageId',CAST(message_id AS CHAR),
    'senderId',CAST(sender_id AS CHAR),'receiverId',NULL,'sequence',CAST(seq AS CHAR),'groupId',CAST(group_id AS CHAR)) FROM tb_group_message`)
  const tasks = await sqlRows(`SELECT JSON_OBJECT('clientId',COALESCE(message.client_msg_id,group_message.client_msg_id),'messageId',CAST(outbox.message_id AS CHAR),
    'targetUserId',CAST(target_user_id AS CHAR),'status',outbox.status,'attempts',attempts,'revision',delivery_revision,
    'bodyPresent',IF(payload IS NULL,0,1)) FROM tb_message_outbox outbox
    LEFT JOIN tb_chat_message message ON message.message_id=outbox.message_id
    LEFT JOIN tb_group_message group_message ON group_message.message_id=outbox.message_id`)
  const counters = await sqlRows("SELECT JSON_OBJECT('key',conversation_key,'sequence',CAST(last_seq AS CHAR)) FROM tb_conversation_sequence")
  const memberships = await sqlRows("SELECT JSON_OBJECT('groupId',CAST(group_id AS CHAR),'userId',CAST(user_id AS CHAR)) FROM tb_group_member")
  return verifyDatabase(tracker.expected(), messages, tasks, counters, memberships)
}

async function createGroup(members) {
  const response = await fetch('http://127.0.0.1:33921/group/create', {
    method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${members[0].token}` },
    body: JSON.stringify({ groupName: `load ${runId}`, memberIds: members.slice(1).map(member => member.userId) }),
    signal: AbortSignal.timeout(10000)
  })
  const body = await response.json()
  assert(response.ok && body.code === 200, 'Synthetic group creation failed')
  const value = body.data.groupId
  assert((typeof value === 'string' || Number.isSafeInteger(value)) && /^[1-9]\d{0,18}$/.test(String(value)), 'Invalid group identifier')
  return String(value)
}

function groupWorkload(members, groupId, senders) {
  return members.slice(0, senders).map(sender => ({ sender, groupId,
    recipientIds: members.filter(member => member.userId !== sender.userId).map(member => member.userId) }))
}

async function processSnapshot() {
  try {
    const { stdout } = await execute('/bin/ps', ['-o', 'pid=,rss=,%cpu=', '-p', children.map(child => child.pid).join(',')], { timeout: 3000 })
    return stdout.trim().split('\n').filter(Boolean).map(line => {
      const [pid, rssKiB, cpuPercentPsReported] = line.trim().split(/\s+/).map(Number)
      return { instance: children.find(child => child.pid === pid)?.instance, rssKiB, cpuPercentPsReported }
    })
  } catch { return null }
}

async function runStage(stage, plan, workload) {
  phase = stage
  const result = { stage, scenario: options.scenario, size: plan.size, users: plan.users, senders: plan.senders,
    scheduling: plan.scheduling,
    pairs: options.scenario === 'private' ? plan.size : undefined, connectedWebSockets: plan.users,
    recipientsPerMessage: plan.recipientsPerMessage, plannedRecipientDeliveries: plan.plannedRecipientDeliveries,
    targetMessagesPerSecond: plan.senders * options.ratePerPair,
    targetRecipientDeliveriesPerSecond: plan.senders * options.ratePerPair * plan.recipientsPerMessage, planned: plan.planned,
    samples: [], processBefore: await processSnapshot(), harnessRssBefore: process.memoryUsage().rss }
  stageResults.push(result)
  let sampling
  let sampleErrors = 0
  const sample = () => {
    if (sampling) return sampling
    sampling = (async () => {
      try {
        const response = await fetch('http://127.0.0.1:33921/ops/delivery/summary', {
          headers: { 'X-SChat-Ops-Token': opsSecret, 'X-SChat-Ops-Actor': 'load:baseline' }, signal: AbortSignal.timeout(2000)
        })
        const body = await response.json()
        assert(response.ok && body.code === 200, 'metrics unavailable')
        result.samples.push({ elapsedMs: result.startedMonotonic === undefined ? 0 : performance.now() - result.startedMonotonic,
          unconfirmed: body.data.unconfirmedCount, failed: body.data.failedCount, oldestSeconds: body.data.oldestUnconfirmedSeconds })
      } catch { sampleErrors++ }
      finally { sampling = null }
    })()
    return sampling
  }
  await sample()
  const histogram = monitorEventLoopDelay({ resolution: 20 })
  histogram.enable()
  const utilization = performance.eventLoopUtilization()
  const started = performance.now()
  result.startedMonotonic = started
  const timer = setInterval(() => { void sample() }, 1000)
  try {
    for (let index = 0; index < result.planned; index++) {
      const due = started + scheduledOffsetMs(plan, index, options.ratePerPair)
      while (performance.now() < due) await delay(Math.max(1, due - performance.now()))
      if (interrupted) throw new Error('Load run interrupted')
      const abort = generatorLagAbort(performance.now(), due, options.maxGeneratorLagMs, index, result.planned)
      if (abort) {
        result.generatorAbort = abort
        tracker.unsent(stage, abort.remainingOffers, 'GENERATOR_LAG_ABORT')
        break
      }
      send(workload[index % workload.length], stage, index, due)
    }
    while (performance.now() < started + options.seconds * 1000) await delay(5)
  } finally {
    result.offerWindowMs = performance.now() - started
    clearInterval(timer)
    if (sampling) await sampling
    histogram.disable()
    result.generatorEventLoopMs = { mean: histogram.count ? histogram.mean / 1e6 : null,
      p95: histogram.count ? histogram.percentile(95) / 1e6 : null,
      p99: histogram.count ? histogram.percentile(99) / 1e6 : null, max: histogram.count ? histogram.max / 1e6 : null }
    result.generatorEventLoopUtilization = performance.eventLoopUtilization(utilization).utilization
  }
  if (sampleErrors) tracker.issue(stage, 'METRICS_SAMPLE_FAILED')
  try {
    await until('client acknowledgements and terminal Outbox rows', async () => tracker.pending === 0
      && await completedTasks(), options.drainSeconds * 1000)
  } catch (error) {
    if (interrupted) throw error
    tracker.issue(stage, 'DRAIN_TIMEOUT')
  }
  result.drainMs = Math.max(0, performance.now() - started - result.offerWindowMs)
  result.metrics = tracker.summary(stage)
  result.database = await reconcile()
  result.verdict = stageVerdict(result.metrics, result.database, options, result.planned, result.plannedRecipientDeliveries)
  result.offeredPerSecond = result.metrics.sent * 1000 / result.offerWindowMs
  const offerEnd = started + result.offerWindowMs
  result.acknowledgedWithinOfferWindow = tracker.expected(stage).filter(record => record.ackedAt !== null && record.ackedAt <= offerEnd).length
  result.receivedWithinOfferWindow = tracker.expected(stage).flatMap(record => record.observations)
    .filter(observation => observation.receivedAt !== null && observation.receivedAt <= offerEnd).length
  result.receivedWithinOfferWindowPerSecond = result.receivedWithinOfferWindow * 1000 / result.offerWindowMs
  result.processAfter = await processSnapshot()
  result.harnessRssAfter = process.memoryUsage().rss
  delete result.startedMonotonic
  console.log(`${stage}: scenario=${options.scenario} users=${plan.users} senders=${plan.senders} sent=${result.metrics.sent}/${result.planned} ACK=${result.metrics.senderAcknowledged} deliveries=${result.metrics.uniqueReceived}/${result.plannedRecipientDeliveries} ACK_p95=${result.metrics.sendAckMs.p95?.toFixed(1)}ms receive_p95=${result.metrics.receiveMs.p95?.toFixed(1)}ms passed=${result.verdict.passed}`)
  return result.verdict.passed
}

async function stop(child) {
  if (child.exitCode !== null || child.signalCode !== null || child.spawnFailed) return
  child.kill('SIGTERM')
  try {
    await until('test JVM shutdown', () => child.exitCode !== null || child.signalCode !== null, 10000)
  } catch {
    child.kill('SIGKILL')
    await delay(250)
  }
}

try {
  console.log(`Load artifacts: ${output}`)
  for (const port of [33920, 33921, 33922]) {
    await new Promise((resolve, reject) => {
      const server = createServer()
      server.once('error', reject)
      server.listen(port, '127.0.0.1', () => server.close(resolve))
    })
  }
  assert.equal(await sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()'), '0', 'Use an empty schat_load database')
  const redisArgs = ['-h', '127.0.0.1', '-p', String(options.redisPort), '--raw']
  const redisEnvironment = { ...process.env }
  delete redisEnvironment.REDISCLI_AUTH
  assert.equal((await execute(redis, [...redisArgs, 'DBSIZE'], { env: redisEnvironment, timeout: 5000 })).stdout.trim(), '0', 'Use an empty isolated Redis; this script never flushes it')
  report.environment.mysql = await sql('SELECT VERSION()')
  const info = (await execute(redis, [...redisArgs, 'INFO', 'server'], { env: redisEnvironment, timeout: 5000 })).stdout
  report.environment.redis = info.match(/^redis_version:(.+)$/m)?.[1].trim()
  const java = await execute('java', ['-version'], { timeout: 5000 })
  report.environment.java = java.stderr.trim().split('\n')[0]
  report.environment.jarModifiedAt = Object.fromEntries(['s-server', 's-user'].map(module => [module,
    statSync(join(root, module, 'target', `${module}-0.0.1-SNAPSHOT.jar`)).mtime.toISOString()]))
  try {
    report.environment.gitCommit = (await execute('git', ['rev-parse', 'HEAD'], { cwd: root })).stdout.trim()
    report.environment.workingTreeDirty = (await execute('git', ['status', '--porcelain'], { cwd: root })).stdout.trim().length > 0
  } catch { report.environment.workingTreeDirty = null }
  const auth = launch('s-user', 33920, 'load-auth')
  await ready(auth, 33920)
  const identities = []
  for (let index = 0; index < Math.max(...options.stages.map(stage => stage.users)); index++) {
    const credentials = { username: `load_${runId}_${index}`, password: 'Load-test-password-123' }
    await api('/auth/register', { ...credentials, nickname: `load ${index}` })
    identities.push(await api('/auth/login', credentials))
  }
  const nodeA = launch('s-server', 33921, 'load-a:33921')
  await ready(nodeA, 33921)
  const nodeB = launch('s-server', 33922, 'load-b:33922')
  await ready(nodeB, 33922)
  let sharedGroup
  for (const [stageIndex, plan] of options.stages.entries()) {
    phase = 'warm'
    let workload
    if (options.scenario === 'private') {
      while (pairs.length < plan.size) {
        const index = pairs.length
        const senderPort = index % 2 === 0 ? 33921 : 33922
        const receiverPort = senderPort === 33921 ? 33922 : 33921
        pairs.push({ sender: await connect(identities[index * 2], senderPort), receiver: await connect(identities[index * 2 + 1], receiverPort) })
        send(pairs[index], 'warm', index, performance.now())
      }
      workload = pairs.slice(0, plan.size)
    } else {
      while (peers.length < plan.users) {
        const index = peers.length
        const peer = await connect(identities[index], index % 2 === 0 ? 33921 : 33922)
        peer.token = identities[index].token
      }
      const members = peers.slice(0, plan.users)
      if (options.scenario === 'group-fanout' || sharedGroup === undefined) {
        const groupId = await createGroup(members)
        if (options.scenario === 'group-hotspot') sharedGroup = groupId
        workload = groupWorkload(members, groupId, plan.senders)
        send(workload[0], 'warm', stageIndex, performance.now())
      } else workload = groupWorkload(members, sharedGroup, plan.senders)
    }
    await until('warmup delivery and durable confirmation', async () => tracker.pending === 0 && await completedTasks())
    assert(Object.keys(tracker.summary('warm').issues).length === 0, 'Warmup protocol validation failed')
    const passed = await runStage(`s${stageIndex + 1}`, plan, workload)
    if (!passed) break
  }
  report.warmupMessages = tracker.summary('warm').sent
  report.finalDatabase = await reconcile()
  report.highestPassingTestedTargetMessagesPerSecond = Math.max(0, ...stageResults.filter(stage => stage.verdict?.passed)
    .map(stage => stage.targetMessagesPerSecond))
  const lastPassing = stageResults.filter(stage => stage.verdict?.passed).at(-1)
  report.lastPassingTestedShape = lastPassing ? { users: lastPassing.users, senders: lastPassing.senders,
    recipientsPerMessage: lastPassing.recipientsPerMessage, targetMessagesPerSecond: lastPassing.targetMessagesPerSecond,
    targetRecipientDeliveriesPerSecond: lastPassing.targetRecipientDeliveriesPerSecond } : null
  report.passed = stageResults.length === options.stages.length && stageResults.every(stage => stage.verdict?.passed)
    && report.finalDatabase.valid && Object.keys(tracker.summary('bootstrap').issues).length === 0
} catch (error) {
  report.failure = { message: error.message }
  report.passed = false
} finally {
  phase = 'shutdown'
  shuttingDown = true
  for (const peer of peers) {
    clearInterval(peer.heartbeat)
    try { peer.socket.close() } catch {}
  }
  for (const child of [...children].reverse()) await stop(child)
  report.finishedAt = new Date().toISOString()
  report.socketDiagnostics = { connections: peers.length, applicationReady: peers.filter(peer => peer.ready).length,
    readinessProbes: peers.reduce((sum, peer) => sum + peer.readinessProbes, 0), receivedFrames: Object.fromEntries(receivedFrameCounts),
    bootstrapIssues: tracker.summary('bootstrap').issues, warmupIssues: tracker.summary('warm').issues }
  report.environment.loadAverageAtEnd = loadavg()
  writeFileSync(join(output, 'report.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(`Report: ${join(output, 'report.json')}`)
  if (report.failure) console.error(report.failure.message)
  console.log(`LOAD BASELINE ${report.passed ? 'PASSED' : 'FAILED'}`)
  process.exitCode = report.passed ? 0 : 1
}
