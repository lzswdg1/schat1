import assert from 'node:assert/strict'
import { spawn, execFileSync } from 'node:child_process'
import { mkdtempSync, openSync, closeSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto'
import { createServer } from 'node:net'
import { createServer as createHttpServer } from 'node:http'
import { TcpFaultProxy } from './lib/tcp-fault-proxy.mjs'

const root = resolve(import.meta.dirname, '..')
const jdbcUrl = process.env.SCHAT_SMOKE_MYSQL_URL
assert(jdbcUrl, 'Set SCHAT_SMOKE_MYSQL_URL to an isolated schat_smoke database')
const database = new URL(jdbcUrl.replace(/^jdbc:/, ''))
assert.equal(database.pathname, '/schat_smoke', 'Only the isolated schat_smoke database is allowed')
assert(['127.0.0.1', 'localhost'].includes(database.hostname), 'Only a local test database is allowed')
const mysql = process.env.SCHAT_SMOKE_MYSQL_BIN || 'mysql'
const redisPort = process.env.SCHAT_SMOKE_REDIS_PORT || '33672'
const password = process.env.SCHAT_SMOKE_MYSQL_PASSWORD || 'schat-test-only'
const logs = mkdtempSync(join(tmpdir(), 'schat-process-smoke-'))
const processes = new Set()
const sockets = new Set()
const protocolErrors = []
const secret = randomBytes(48).toString('hex')
const opsSecret = randomBytes(48).toString('hex')
const alertSecret = randomBytes(48).toString('hex')
const alertAttempts = new Map()
const acceptedAlerts = []
const alertErrors = []
const webhook = createHttpServer((request, response) => {
  const chunks = []
  let length = 0
  request.on('error', () => request.destroy())
  request.on('data', chunk => {
    length += chunk.length
    if (length > 65536) request.destroy()
    else chunks.push(chunk)
  })
  request.on('end', () => {
    try {
      const body = Buffer.concat(chunks)
      const timestamp = request.headers['x-schat-alert-timestamp']
      const signature = request.headers['x-schat-alert-signature']
      const expected = `sha256=${createHmac('sha256', alertSecret).update(`${timestamp}\n`).update(body).digest('hex')}`
      assert.equal(typeof signature, 'string', 'Missing alert signature')
      assert.equal(signature.length, expected.length, 'Invalid alert signature length')
      assert(timingSafeEqual(Buffer.from(signature), Buffer.from(expected)), 'Invalid alert signature')
      assert(Math.abs(Date.now() - Number(timestamp)) < 30000, 'Invalid alert signing timestamp')
      const event = JSON.parse(body.toString('utf8'))
      assert.equal(request.headers['x-schat-alert-id'], event.eventId)
      assert.equal(event.schemaVersion, 1)
      assert.equal(event.instanceId, 'smoke-a:33821')
      assert(!body.includes(Buffer.from(alertSecret)), 'Alert body must not contain credentials')
      const previous = alertAttempts.get(event.eventId)
      if (previous) {
        assert(body.equals(previous.body), 'Retries must preserve event ID and body')
        previous.attempts++
        acceptedAlerts.push(event)
        response.writeHead(204).end()
      } else {
        alertAttempts.set(event.eventId, { body, attempts: 1 })
        response.writeHead(503).end()
      }
    } catch {
      alertErrors.push('Invalid signed alert request')
      response.writeHead(400).end()
    }
  })
})
const redisProxy = new TcpFaultProxy(Number(redisPort))
const mysqlProxy = new TcpFaultProxy(Number(database.port || 3306))
const proxiedDatabase = new URL(database)
proxiedDatabase.hostname = '127.0.0.1'
proxiedDatabase.port = '33824'
proxiedDatabase.searchParams.set('connectTimeout', '1000')
proxiedDatabase.searchParams.set('socketTimeout', '4000')

function sql(statement) {
  return execFileSync(mysql, ['--no-defaults', '--protocol=TCP', '-h', database.hostname, '-P', database.port || '3306',
    '-u', 'root', '--batch', '--skip-column-names', 'schat_smoke', '-e', statement], {
    env: { ...process.env, MYSQL_PWD: password }, encoding: 'utf8', timeout: 5000
  }).trim()
}

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

async function until(description, predicate, timeout = 30000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const result = await predicate()
    if (result) return result
    await delay(100)
  }
  throw new Error(`Timed out: ${description}`)
}

function launch(module, port, instance) {
  const log = join(logs, `${instance}.log`)
  const output = openSync(log, 'a')
  const args = ['-Xms64m', '-Xmx256m', '-jar', join(root, module, 'target', `${module}-0.0.1-SNAPSHOT.jar`),
    `--server.port=${port}`, '--server.address=127.0.0.1', '--spring.config.import=',
    `--spring.datasource.url=${port === 33821 ? `jdbc:${proxiedDatabase}` : jdbcUrl}`,
    '--spring.datasource.username=root', `--spring.datasource.password=${password}`,
    '--spring.datasource.hikari.connection-timeout=2000', '--spring.datasource.hikari.validation-timeout=1000',
    '--spring.cloud.nacos.discovery.enabled=false', '--spring.cloud.discovery.enabled=false',
    '--spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration',
    '--spring.data.redis.host=127.0.0.1', `--spring.data.redis.port=${port === 33822 ? redisProxy.port : redisPort}`,
    '--spring.data.redis.password=', '--spring.data.redis.connect-timeout=1s', '--spring.data.redis.timeout=3s',
    '--schat.login.trusted-proxies=',
    `--schat.instance.id=${instance}`, '--schat.shutdown.wait-seconds=0',
    '--schat.delivery-alert.allow-loopback-http=true', '--schat.delivery-ops.monitor-interval-ms=1000',
    '--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl']
  const child = spawn('java', args, { cwd: root, stdio: ['ignore', output, output], env: {
    ...process.env, SCHAT_JWT_SECRET: secret, SCHAT_INTERNAL_SECRET: secret,
    SCHAT_DELIVERY_OPS_ENABLED: String(module === 's-server' && port === 33821),
    SCHAT_DELIVERY_OPS_SECRET: opsSecret, SCHAT_DELIVERY_OPS_AUTO_CLEANUP_ENABLED: 'false',
    SCHAT_DELIVERY_ALERT_ENABLED: String(module === 's-server' && port === 33821),
    SCHAT_DELIVERY_ALERT_URL: 'http://127.0.0.1:33825/alerts', SCHAT_DELIVERY_ALERT_SECRET: alertSecret,
    MYSQL_PASSWORD: password, REDIS_PASSWORD: ''
  } })
  closeSync(output)
  child.logPath = log
  processes.add(child)
  return child
}

async function ready(child, port) {
  await until(`service on ${port}`, async () => {
    if (child.exitCode !== null) throw new Error(`Service exited: ${child.logPath}\n${readFileSync(child.logPath, 'utf8').slice(-6000)}`)
    try { return (await fetch(`http://127.0.0.1:${port}/v3/api-docs`, { signal: AbortSignal.timeout(1500) })).ok }
    catch { return false }
  }, 90000)
}

async function api(port, path, body, token) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
  })
  const result = await response.json()
  assert.equal(result.code, 200, `API failed ${path}: ${JSON.stringify(result)}`)
  return result.data
}

async function inspectApi(port, path, { body, token, headers = {} } = {}) {
  const response = await fetch(`http://127.0.0.1:${port}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
  })
  return { status: response.status, body: await response.json() }
}

async function peer(port, user) {
  const socket = new WebSocket(`ws://127.0.0.1:${port}/ws/chat/${user.user.id}?token=${encodeURIComponent(user.token)}`)
  sockets.add(socket)
  const packets = []
  socket.addEventListener('message', event => {
    const packet = JSON.parse(event.data)
    if (packet.type === 'NEW_MESSAGE' && typeof packet.data?.id !== 'string') protocolErrors.push('Unsafe message ID')
    packets.push(packet)
  })
  await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('WebSocket open timeout')), 10000)
    socket.addEventListener('open', () => { clearTimeout(timeout); resolve() }, { once: true })
    socket.addEventListener('error', () => { clearTimeout(timeout); reject(new Error('WebSocket open failed')) }, { once: true })
  })
  return {
    socket, packets,
    send: value => socket.send(JSON.stringify(value)),
    wait: (predicate, timeout = 30000) => until('WebSocket event', () => packets.find(predicate), timeout)
  }
}

async function ops(port, path, body, headers = {}) {
  const response = await fetch(`http://127.0.0.1:${port}/ops/delivery${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
  })
  return { status: response.status, requestId: response.headers.get('X-SChat-Ops-Request-Id'), body: await response.json() }
}

function number(value) {
  assert(/^\d+$/.test(String(value)), 'Expected a numeric identifier')
  return String(value)
}

function acknowledge(client, packet) {
  client.send({ msgType: 'DELIVERY_ACK', messageId: packet.data.id, deliveryRevision: packet.deliveryRevision || 0 })
}

async function send(client, recipient, clientId, content = clientId) {
  client.send({ msgType: 'TEXT', toId: String(recipient), clientMsgId: clientId, content })
  const packet = await client.wait(packet => packet.type === 'MSG_ACK' && packet.data.clientMsgId === clientId)
  assert.equal(packet.data.status, 'success')
  assert.equal(typeof packet.data.messageId, 'string', 'Message IDs must survive ordinary JSON.parse')
  return number(packet.data.messageId)
}

async function stop(child, signal = 'SIGTERM') {
  if (child.exitCode !== null || child.signalCode !== null) return
  child.kill('SIGCONT')
  child.kill(signal)
  await until('process exit', () => child.exitCode !== null || child.signalCode !== null, 10000)
}

try {
  for (const port of [33820, 33821, 33822, 33823, 33824, 33825]) {
    await new Promise((resolve, reject) => {
      const listener = createServer()
      listener.once('error', reject)
      listener.listen(port, '127.0.0.1', () => listener.close(resolve))
    })
  }
  assert.equal(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()'), '0',
    'Use an empty schat_smoke database; this script never resets an existing schema')
  await redisProxy.listen(33823)
  await mysqlProxy.listen(33824)
  await new Promise((resolve, reject) => {
    webhook.once('error', reject)
    webhook.listen(33825, '127.0.0.1', resolve)
  })
  console.log(`Logs: ${logs}`)
  const auth = launch('s-user', 33820, 'smoke-auth')
  await ready(auth, 33820)
  const suffix = randomBytes(4).toString('hex')
  const users = []
  for (const name of ['sender', 'receiver']) {
    const credentials = { username: `${name}_${suffix}`, password: 'Smoke-test-password-123' }
    await api(33820, '/auth/register', { ...credentials, nickname: name })
    users.push(await api(33820, '/auth/login', credentials))
  }
  const [sender, receiver] = users
  const receiverId = number(receiver.user.id)
  const nodeA = launch('s-server', 33821, 'smoke-a:33821')
  await ready(nodeA, 33821)
  const nodeB = launch('s-server', 33822, 'smoke-b:33822')
  await ready(nodeB, 33822)
  const source = await peer(33821, sender)
  const target = await peer(33822, receiver)
  const messageId = await send(source, receiverId, 'kill-receiver')
  await target.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === messageId && packet.deliveryId)
  await until('awaiting client acknowledgement', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${messageId} AND target_user_id=${receiverId}`) === 'AWAITING_ACK')
  await stop(nodeB, 'SIGKILL')
  const reconnected = await peer(33821, receiver)
  const replayed = await reconnected.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === messageId && packet.deliveryId)
  acknowledge(reconnected, replayed)
  await until('delivery after receiver process kill', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${messageId} AND target_user_id=${receiverId}`) === 'DELIVERED')
  console.log('PASS receiver SIGKILL, reconnect to another process, durable ACK')

  const lostAckId = await send(source, receiverId, 'lost-client-ack')
  await reconnected.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === lostAckId && packet.deliveryId)
  await until('ACK-timeout redelivery', () => reconnected.packets.filter(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === lostAckId && packet.deliveryId).length >= 2, 20000)
  const duplicate = reconnected.packets.findLast(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === lostAckId && packet.deliveryId)
  acknowledge(reconnected, duplicate)
  await until('lost ACK recovered', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${lostAckId} AND target_user_id=${receiverId}`) === 'DELIVERED')
  console.log('PASS actual ACK timeout and repeated delivery with the same message ID')

  reconnected.socket.close()
  await until('receiver offline', () => reconnected.socket.readyState === WebSocket.CLOSED)
  const recalledId = await send(source, receiverId, 'recall-pending', 'must-never-replay-this-body')
  await api(33821, `/chat/${receiverId}/messages/${recalledId}/recall`, {}, sender.token)
  const afterRecall = await peer(33821, receiver)
  const tombstone = await afterRecall.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === recalledId && packet.deliveryRevision > 0)
  assert.equal(tombstone.data.recalled, true)
  assert.equal(tombstone.data.content, '')
  assert(!afterRecall.packets.some(packet => packet.type === 'NEW_MESSAGE' && packet.data.content === 'must-never-replay-this-body'))
  acknowledge(afterRecall, tombstone)
  await until('recall revision acknowledged', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${recalledId} AND target_user_id=${receiverId}`) === 'DELIVERED')
  console.log('PASS offline recall, original body fenced, tombstone acknowledged')

  const restartB = launch('s-server', 33822, 'smoke-b:33822')
  await ready(restartB, 33822)
  const finalReceiver = await peer(33822, receiver)
  restartB.kill('SIGSTOP')
  await delay(200)
  const queuedRecallId = await send(source, receiverId, 'recall-queued-stream', 'obsolete-queued-body')
  await until('old body queued while receiver JVM is paused', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${queuedRecallId} AND target_user_id=${receiverId}`) === 'AWAITING_ACK')
  await api(33821, `/chat/${receiverId}/messages/${queuedRecallId}/recall`, {}, sender.token)
  restartB.kill('SIGCONT')
  const queuedTombstone = await finalReceiver.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === queuedRecallId && packet.deliveryRevision > 0)
  assert(!finalReceiver.packets.some(packet => packet.type === 'NEW_MESSAGE' && packet.data.content === 'obsolete-queued-body'))
  acknowledge(finalReceiver, queuedTombstone)
  await until('queued recall revision acknowledged', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${queuedRecallId} AND target_user_id=${receiverId}`) === 'DELIVERED')
  console.log('PASS SIGSTOP queued body, recall, SIGCONT fences stale Stream payload')
  const group = await api(33821, '/group/create', { groupName: 'smoke group', memberIds: [receiverId] }, sender.token)
  const groupId = number(group.groupId)
  finalReceiver.socket.close()
  await until('group recipient offline', () => finalReceiver.socket.readyState === WebSocket.CLOSED)
  source.send({ msgType: 'GROUP_TEXT', groupId, toId: groupId, clientMsgId: 'dismiss-pending', content: 'dismissed group body' })
  const groupAck = await source.wait(packet => packet.type === 'MSG_ACK' && packet.data.clientMsgId === 'dismiss-pending')
  const groupMessageId = number(groupAck.data.messageId)
  await api(33821, `/group/dismiss?groupId=${groupId}`, undefined, sender.token)
  assert.equal(sql(`SELECT COUNT(*) FROM tb_message_outbox WHERE message_id=${groupMessageId} AND status <> 'CANCELLED'`), '0')
  const afterDismiss = await peer(33822, receiver)
  await delay(1500)
  assert(!afterDismiss.packets.some(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === groupMessageId))
  console.log('PASS group dismissal cancels pending recipient deliveries across processes')

  const rateId = await send(source, receiverId, 'ack-with-exhausted-chat-budget')
  const ratePacket = await afterDismiss.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === rateId && packet.deliveryId)
  for (let attempt = 0; attempt < 80; attempt++) {
    afterDismiss.send({ msgType: 'TEXT', toId: String(sender.user.id), clientMsgId: `chat-flood-${attempt}`, content: 'rate test' })
  }
  await afterDismiss.wait(packet => packet.type === 'ERROR' && packet.data.message.includes('频繁'))
  acknowledge(afterDismiss, ratePacket)
  await until('receipt budget survives chat flood', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${rateId} AND target_user_id=${receiverId}`) === 'DELIVERED')
  console.log('PASS ACK is accepted after the same client exhausts its chat budget')

  const membershipGroup = await api(33821, '/group/create', { groupName: 'membership cache', memberIds: [receiverId] }, sender.token)
  const membershipGroupId = number(membershipGroup.groupId)
  source.send({ msgType: 'GROUP_TEXT', groupId: membershipGroupId, toId: membershipGroupId, clientMsgId: 'warm-membership', content: 'warm group member cache' })
  const warmAck = await source.wait(packet => packet.type === 'MSG_ACK' && packet.data.clientMsgId === 'warm-membership')
  const warmPacket = await afterDismiss.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === warmAck.data.messageId && packet.deliveryId)
  acknowledge(afterDismiss, warmPacket)
  await api(33821, '/group/kick', { groupId: membershipGroupId, userId: receiverId }, sender.token)
  await until('membership invalidation published after commit', () => sql(`SELECT revision = published_revision FROM tb_group_cache_invalidation WHERE group_id=${membershipGroupId}`) === '1')
  const forbidden = await fetch(`http://127.0.0.1:33822/group/${membershipGroupId}/sync`, {
    headers: { Authorization: `Bearer ${receiver.token}` }, signal: AbortSignal.timeout(10000)
  }).then(response => response.json())
  assert.notEqual(forbidden.code, 200, 'A stale cache must not authorize a kicked user on the other node')
  source.send({ msgType: 'GROUP_TEXT', groupId: membershipGroupId, toId: membershipGroupId, clientMsgId: 'after-kick', content: 'remaining members only' })
  const afterKick = await source.wait(packet => packet.type === 'MSG_ACK' && packet.data.clientMsgId === 'after-kick')
  const afterKickId = number(afterKick.data.messageId)
  assert.equal(sql(`SELECT COUNT(*) FROM tb_message_outbox WHERE message_id=${afterKickId} AND target_user_id=${receiverId}`), '0')
  console.log('PASS cross-node membership invalidation and immediate authorization revocation after kick')

  assert.equal((await ops(33822, '/summary')).status, 404, 'Operations API must be off by default')
  assert.equal((await ops(33821, '/summary')).status, 401)
  assert.equal((await ops(33821, '/summary', undefined, { Authorization: `Bearer ${sender.token}`, 'X-Internal-Token': secret })).status, 401)
  assert.equal((await ops(33821, '/summary', undefined, { 'X-SChat-Ops-Token': secret, 'X-SChat-Ops-Actor': 'smoke' })).status, 401)
  const opsHeaders = { 'X-SChat-Ops-Token': opsSecret, 'X-SChat-Ops-Actor': 'smoke:oncall' }
  assert.equal((await ops(33821, '/summary', undefined, opsHeaders)).status, 200)
  afterDismiss.socket.close()
  await until('operations recipient offline', () => afterDismiss.socket.readyState === WebSocket.CLOSED)
  const opsMessageId = await send(source, receiverId, 'operations-failed-delivery', 'private-ops-body-must-not-leak')
  sql(`UPDATE tb_message_outbox SET status='FAILED', attempts=12, claim_token=NULL, locked_until=NULL,
    last_error='private-parser-details-must-not-leak' WHERE message_id=${opsMessageId} AND target_user_id=${receiverId}`)
  const failedPage = await ops(33821, '/failed', undefined, opsHeaders)
  assert.equal(failedPage.status, 200)
  assert(!JSON.stringify(failedPage.body).includes('must-not-leak'))
  const failedTask = failedPage.body.data.find(task => task.messageId === opsMessageId)
  assert(failedTask)
  const retry = await ops(33821, '/retry', { items: [{ id: failedTask.id, revision: failedTask.revision }], reason: 'route recovery smoke test' }, opsHeaders)
  assert.equal(retry.status, 200)
  assert.equal(retry.body.data[0].outcome, 'REQUEUED')
  assert.equal(sql(`SELECT COUNT(*) FROM tb_delivery_ops_audit WHERE request_id='${retry.requestId}' AND operation='RETRY' AND outcome='REQUEUED'`), '1')
  const cancelledTask = sql(`SELECT id FROM tb_message_outbox WHERE message_id=${groupMessageId} AND target_user_id=${receiverId}`)
  const stale = await ops(33821, '/retry', { items: [{ id: number(cancelledTask), revision: 0 }], reason: 'stale view must not revive cancellation' }, opsHeaders)
  assert.equal(stale.body.data[0].outcome, 'SKIPPED')
  await until('cache-only task completed', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${opsMessageId} AND target_user_id=${number(sender.user.id)}`) === 'PUBLISHED')
  sql(`UPDATE tb_message_outbox SET terminal_at=TIMESTAMPADD(DAY,-40,CURRENT_TIMESTAMP(3))
    WHERE (message_id=${opsMessageId} AND payload IS NULL) OR message_id=${groupMessageId}`)
  assert.equal((await ops(33821, '/cleanup?olderThanDays=30', undefined, opsHeaders)).body.data, 1)
  const cleanup = await ops(33821, '/cleanup', { olderThanDays: 30, limit: 100, reason: 'retention smoke test' }, opsHeaders)
  assert.equal(cleanup.status, 200)
  assert.equal(cleanup.body.data, 1)
  assert.equal(sql(`SELECT status FROM tb_message_outbox WHERE id=${number(cancelledTask)}`), 'CANCELLED')
  assert.equal(sql(`SELECT COUNT(*) FROM tb_delivery_ops_audit WHERE request_id='${cleanup.requestId}' AND operation='CLEANUP'`), '1')
  assert.equal((await ops(33821, '/audit', undefined, opsHeaders)).status, 200)
  console.log('PASS opt-in operations security, metadata privacy, revision-fenced retry, transactional audit and safe retention')

  const faultUsers = []
  for (const name of ['fault_sender', 'fault_receiver']) {
    const credentials = { username: `${name}_${suffix}`, password: 'Smoke-test-password-123' }
    await api(33820, '/auth/register', { ...credentials, nickname: name })
    faultUsers.push(await api(33820, '/auth/login', credentials))
  }
  const [faultSender, faultReceiver] = faultUsers
  const faultSenderId = number(faultSender.user.id)
  const faultReceiverId = number(faultReceiver.user.id)
  const faultSource = await peer(33821, faultSender)
  const faultTarget = await peer(33822, faultReceiver)
  const warmId = await send(faultSource, faultReceiverId, 'fault-warmup')
  const warm = await faultTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === warmId && packet.deliveryId)
  acknowledge(faultTarget, warm)
  await until('fault warmup acknowledged', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${warmId} AND target_user_id=${faultReceiverId}`) === 'DELIVERED')

  assert(redisProxy.stats.activeConnections > 0, 'The recipient JVM must actually use the Redis proxy')
  redisProxy.cut()
  const partitionId = await send(faultSource, faultReceiverId, 'redis-link-cut')
  await delay(4000)
  assert.equal(sql(`SELECT COUNT(*) FROM tb_chat_message WHERE message_id=${partitionId}`), '1')
  assert.equal(sql(`SELECT COUNT(*) FROM tb_message_outbox WHERE message_id=${partitionId}`), '2')
  assert.notEqual(sql(`SELECT status FROM tb_message_outbox WHERE message_id=${partitionId} AND target_user_id=${faultReceiverId}`), 'DELIVERED')
  assert(!faultTarget.packets.some(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === partitionId))
  assert(redisProxy.stats.rejectedConnections > 0, 'The recipient JVM must attempt and fail Redis reconnection')
  redisProxy.restore()
  const recovered = await faultTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === partitionId && packet.deliveryId)
  acknowledge(faultTarget, recovered)
  await until('same WebSocket recovers after Redis link healing', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${partitionId} AND target_user_id=${faultReceiverId}`) === 'DELIVERED')
  assert.equal(faultTarget.socket.readyState, WebSocket.OPEN)
  console.log('PASS recipient-only Redis TCP partition retains committed delivery and recovers without a JVM/WebSocket restart')

  redisProxy.cut()
  const failoverId = await send(faultSource, faultReceiverId, 'redis-partition-failover')
  const failoverTarget = await peer(33821, faultReceiver)
  const failoverPacket = await failoverTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === failoverId && packet.deliveryId, 60000)
  acknowledge(failoverTarget, failoverPacket)
  await until('delivery migrates away from partitioned recipient', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${failoverId} AND target_user_id=${faultReceiverId}`) === 'DELIVERED')
  redisProxy.restore()
  await until('isolated JVM reconnects to Redis', () => redisProxy.stats.activeConnections > 0)
  faultTarget.send({ type: 'HEARTBEAT' })
  await until('old recipient connection fenced after healing', () => faultTarget.socket.readyState === WebSocket.CLOSED)
  assert(!faultTarget.packets.some(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === failoverId))
  const afterHealId = await send(faultSource, faultReceiverId, 'partition-owner-preserved')
  const afterHeal = await failoverTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === afterHealId && packet.deliveryId)
  acknowledge(failoverTarget, afterHeal)
  await until('old disconnect leaves the new connection intact', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${afterHealId} AND target_user_id=${faultReceiverId}`) === 'DELIVERED')
  console.log('PASS Redis-partition failover, stale-connection fencing and new ownership preserved after healing')

  const conversationKey = `private:${BigInt(faultSenderId) < BigInt(faultReceiverId) ? `${faultSenderId}:${faultReceiverId}` : `${faultReceiverId}:${faultSenderId}`}`
  const previousSequence = BigInt(sql(`SELECT last_seq FROM tb_conversation_sequence WHERE conversation_key='${conversationKey}'`))
  const previousTasks = sql(`SELECT COUNT(*) FROM tb_message_outbox WHERE target_user_id IN (${faultSenderId},${faultReceiverId})`)
  const beforeFailure = faultSource.packets.length
  assert(mysqlProxy.stats.activeConnections > 0, 'The sender JVM must actually use the MySQL proxy')
  mysqlProxy.cut()
  faultSource.send({ msgType: 'TEXT', toId: faultReceiverId, clientMsgId: 'mysql-link-cut', content: 'retry after database healing' })
  await until('sender receives failure rather than a false success ACK', () => faultSource.packets.slice(beforeFailure)
    .some(packet => packet.type === 'ERROR' && packet.data.message === '服务器处理失败'))
  assert(!faultSource.packets.slice(beforeFailure).some(packet => packet.type === 'MSG_ACK' && packet.data.clientMsgId === 'mysql-link-cut'))
  assert.equal(sql(`SELECT COUNT(*) FROM tb_chat_message WHERE sender_id=${faultSenderId} AND client_msg_id='mysql-link-cut'`), '0')
  assert.equal(sql(`SELECT last_seq FROM tb_conversation_sequence WHERE conversation_key='${conversationKey}'`), previousSequence.toString())
  assert.equal(sql(`SELECT COUNT(*) FROM tb_message_outbox WHERE target_user_id IN (${faultSenderId},${faultReceiverId})`), previousTasks)
  assert(mysqlProxy.stats.rejectedConnections > 0, 'The sender JVM must attempt and fail MySQL reconnection')
  await until('signed monitor-outage notification survives the webhook receiver first returning 503', () =>
    acceptedAlerts.some(event => event.topic === 'MONITOR' && event.state === 'FIRING'))
  mysqlProxy.restore()
  await until('sender connection pool recovers through the healed MySQL proxy', async () =>
    (await ops(33821, '/summary', undefined, opsHeaders)).status === 200)
  const retryId = await send(faultSource, faultReceiverId, 'mysql-link-cut', 'retry after database healing')
  const retryPacket = await failoverTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === retryId && packet.deliveryId)
  acknowledge(failoverTarget, retryPacket)
  await until('database recovery delivers the retried message', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${retryId} AND target_user_id=${faultReceiverId}`) === 'DELIVERED')
  const beforeDuplicate = faultSource.packets.length
  faultSource.send({ msgType: 'TEXT', toId: faultReceiverId, clientMsgId: 'mysql-link-cut', content: 'retry after database healing' })
  const duplicateAck = await until('duplicate retry returns original commit', () => faultSource.packets.slice(beforeDuplicate)
    .find(packet => packet.type === 'MSG_ACK' && packet.data.clientMsgId === 'mysql-link-cut'))
  assert.equal(duplicateAck.data.status, 'duplicate')
  assert.equal(duplicateAck.data.messageId, retryId)
  assert.equal(duplicateAck.data.seq, (previousSequence + 1n).toString())
  assert.equal(sql(`SELECT COUNT(*) FROM tb_chat_message WHERE sender_id=${faultSenderId} AND client_msg_id='mysql-link-cut'`), '1')
  assert.equal(sql(`SELECT COUNT(*) FROM tb_message_outbox WHERE message_id=${retryId}`), '2')
  assert.equal(sql(`SELECT last_seq FROM tb_conversation_sequence WHERE conversation_key='${conversationKey}'`), (previousSequence + 1n).toString())
  console.log('PASS sender-only MySQL TCP partition rejects false ACK, preserves atomicity and recovers idempotently')
  await until('signed monitor recovery reaches the webhook receiver', () =>
    acceptedAlerts.some(event => event.topic === 'MONITOR' && event.state === 'RESOLVED'))
  assert.deepEqual(alertErrors, [])
  const firing = acceptedAlerts.find(event => event.topic === 'MONITOR' && event.state === 'FIRING')
  const resolved = acceptedAlerts.find(event => event.topic === 'MONITOR' && event.state === 'RESOLVED')
  assert.equal(firing.processId, resolved.processId)
  assert(Number(resolved.sequence) > Number(firing.sequence))
  assert(alertAttempts.get(firing.eventId).attempts >= 2)
  assert(alertAttempts.get(resolved.eventId).attempts >= 2)
  console.log('PASS real-JVM signed webhook, retry after HTTP 503, monitor outage and recovery notifications')

  const admissionUsers = []
  for (const role of ['as', 'ar']) {
    const credentials = { username: `${role}_${suffix}`, password: 'Smoke-test-password-123' }
    await delay(1100)
    await api(33820, '/auth/register', { ...credentials, nickname: role })
    await delay(1100)
    admissionUsers.push(await api(33820, '/auth/login', credentials))
  }
  const [admissionSender, admissionReceiver] = admissionUsers
  const admissionSenderId = number(admissionSender.user.id)
  const admissionReceiverId = number(admissionReceiver.user.id)
  const admissionSource = await peer(33821, admissionSender)
  const admissionTarget = await peer(33822, admissionReceiver)
  const admissionGroup = await api(33821, '/group/create', {
    groupName: 'shared admission smoke', memberIds: [admissionReceiverId]
  }, admissionSender.token)
  const admissionGroupId = number(admissionGroup.groupId)
  for (const port of [33821, 33822]) {
    for (const sessionId of [admissionReceiverId, `group_${admissionGroupId}`]) {
      const result = await inspectApi(port, `/chat/${sessionId}/messages`, {
        token: admissionSender.token,
        body: { clientMsgId: `oversized-${port}-${sessionId}`, type: 'text', content: 'x'.repeat(6000) }
      })
      assert.notEqual(result.body.code, 200)
      assert(result.body.message.includes('5000'), 'Oversized HTTP messages must reach the shared length guard')
    }
  }
  assert.equal(sql(`SELECT (SELECT COUNT(*) FROM tb_chat_message WHERE sender_id=${admissionSenderId}) +
    (SELECT COUNT(*) FROM tb_group_message WHERE sender_id=${admissionSenderId})`), '0')
  console.log('PASS HTTP private/group size limits on both chat JVMs with zero persisted messages')

  const searchKeyword = `search-${suffix}`
  const searchBody = `${searchKeyword} <img src=x onerror=window.__smokeXss=1>`
  const searchSent = await api(33821, `/chat/${admissionReceiverId}/messages`, {
    clientMsgId: 'search-contract', type: 'text', content: searchBody
  }, admissionSender.token)
  const searchMessageId = number(searchSent.messageId)
  const searchPacket = await admissionTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === searchMessageId)
  acknowledge(admissionTarget, searchPacket)
  const searchResponse = await inspectApi(33822, `/chat/search?keyword=${encodeURIComponent(searchKeyword)}`, { token: admissionSender.token })
  assert.equal(searchResponse.body.code, 200)
  assert(Array.isArray(searchResponse.body.data), 'Remote search data must be an array')
  const searchResult = searchResponse.body.data.find(result => result.messageId === searchMessageId)
  assert(searchResult)
  assert.equal(typeof searchResult.messageId, 'string')
  assert.equal(searchResult.fromId, admissionSenderId)
  assert.equal(searchResult.content, searchBody)
  assert(Number.isFinite(searchResult.createTime) && searchResult.createTime > 0)
  await api(33821, `/chat/${admissionReceiverId}/messages/${searchMessageId}/recall`, {}, admissionSender.token)
  const searchRecall = await admissionTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === searchMessageId && packet.deliveryRevision > 0)
  acknowledge(admissionTarget, searchRecall)
  const recalledSearch = await inspectApi(33822, `/chat/search?keyword=${encodeURIComponent(searchKeyword)}`, { token: admissionSender.token })
  assert(!recalledSearch.body.data.some(result => result.messageId === searchMessageId))
  console.log('PASS real HTTP cross-node search contract, string IDs, plain body and recall exclusion')

  const beforeBurst = admissionSource.packets.length
  const burstStarted = performance.now()
  const httpBurst = Promise.all(Array.from({ length: 40 }, (_, index) => inspectApi(index % 2 === 0 ? 33821 : 33822,
    `/chat/${Math.floor(index / 2) % 2 === 0 ? admissionReceiverId : `group_${admissionGroupId}`}/messages`, {
      token: admissionSender.token,
      body: { clientMsgId: `burst-http-${index}`, type: 'text', content: 'shared HTTP WS budget' }
    })))
  for (let index = 0; index < 20; index++) {
    admissionSource.send({ msgType: 'TEXT', toId: admissionReceiverId, clientMsgId: `burst-ws-${index}`, content: 'shared HTTP WS budget' })
  }
  const httpResults = await httpBurst
  await until('all burst WebSocket admission responses', () => admissionSource.packets.slice(beforeBurst)
    .filter(packet => packet.type === 'MSG_ACK' || packet.type === 'ERROR').length === 20)
  const burstElapsed = performance.now() - burstStarted
  const wsResults = admissionSource.packets.slice(beforeBurst).filter(packet => packet.type === 'MSG_ACK' || packet.type === 'ERROR')
  const acceptedIds = []
  for (const result of httpResults) {
    if (result.body.code === 200) acceptedIds.push(number(result.body.data.messageId))
    else assert(result.body.message.includes('频繁'), 'Only admission rejection is expected in the HTTP burst')
  }
  for (const result of wsResults) {
    if (result.type === 'MSG_ACK') {
      assert.equal(result.data.status, 'success')
      acceptedIds.push(number(result.data.messageId))
    } else assert(result.data.message.includes('频繁'), 'Only admission rejection is expected in the WS burst')
  }
  assert(acceptedIds.length > 0 && acceptedIds.length < 60, 'The real burst must both send and reject messages')
  assert(acceptedIds.length <= 20 + Math.ceil(burstElapsed * 10 / 1000), 'HTTP/WS and both JVMs must share one user token bucket')
  assert.equal(new Set(acceptedIds).size, acceptedIds.length)
  for (const messageId of acceptedIds) {
    const packet = await admissionTarget.wait(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === messageId)
    acknowledge(admissionTarget, packet)
  }
  const burstIds = acceptedIds.join(',')
  await until('every accepted burst recipient durably acknowledged', () => sql(`SELECT COUNT(*) FROM tb_message_outbox
    WHERE message_id IN (${burstIds}) AND target_user_id=${admissionReceiverId} AND status='DELIVERED'`) === String(acceptedIds.length))
  assert.equal(sql(`SELECT (SELECT COUNT(*) FROM tb_chat_message WHERE sender_id=${admissionSenderId} AND client_msg_id LIKE 'burst-%') +
    (SELECT COUNT(*) FROM tb_group_message WHERE sender_id=${admissionSenderId} AND client_msg_id LIKE 'burst-%')`), String(acceptedIds.length))
  assert.equal(sql(`SELECT COUNT(*) FROM tb_message_outbox WHERE message_id IN (${burstIds})`), String(acceptedIds.length * 2))
  console.log(`PASS shared user admission across HTTP private/group and WS on two JVMs; accepted=${acceptedIds.length}, rejected=${60 - acceptedIds.length}`)

  await delay(6000)
  const loginStarted = performance.now()
  const loginResults = await Promise.all(Array.from({ length: 20 }, (_, index) => inspectApi(33820, '/auth/login', {
    body: { username: `missing_${suffix}`, password: 'Smoke-test-password-123' },
    headers: { 'X-Forwarded-For': `198.51.100.${index}`, 'X-Real-IP': `203.0.113.${index}` }
  })))
  const loginElapsed = performance.now() - loginStarted
  for (const result of loginResults) {
    assert([200, 429].includes(result.status), 'The login flood must not hide server errors')
    assert.notEqual(result.body.code, 200, 'Synthetic invalid credentials must never authenticate')
  }
  const loginAllowed = loginResults.filter(result => result.status !== 429).length
  assert(loginAllowed <= 5 + Math.ceil(loginElapsed / 1000), 'Rotating forwarded headers must not increase the IP budget')
  assert(loginAllowed < 20)
  console.log(`PASS real login rate limiting ignores forged forwarding headers; checked=${loginAllowed}, limited=${20 - loginAllowed}`)
  assert.deepEqual(protocolErrors, [], 'All message paths must preserve IDs through ordinary JSON.parse')
  console.log('ALL PROCESS SMOKE CHECKS PASSED')
} finally {
  redisProxy.restore()
  mysqlProxy.restore()
  for (const socket of sockets) { try { socket.close() } catch {} }
  for (const child of [...processes].reverse()) {
    try { await stop(child) } catch { child.kill('SIGKILL') }
  }
  await redisProxy.close()
  await mysqlProxy.close()
  if (webhook.listening) {
    webhook.closeAllConnections()
    await new Promise(resolve => webhook.close(resolve))
  }
  console.log(`Process logs retained: ${logs}`)
}
