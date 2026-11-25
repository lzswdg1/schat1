import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { mkdtempSync, openSync, closeSync, readFileSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:net'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { randomBytes } from 'node:crypto'

const root = resolve(import.meta.dirname, '..')
const jdbc = process.env.SCHAT_GATEWAY_MYSQL_URL
assert(jdbc, 'Provide an empty local schat_gateway_smoke database')
assert(jdbc.startsWith('jdbc:mysql://'), 'Expected a MySQL JDBC URL')
const database = new URL(jdbc.replace(/^jdbc:/, ''))
assert.equal(database.pathname, '/schat_gateway_smoke')
assert(['127.0.0.1', 'localhost'].includes(database.hostname))
assert(!database.username && !database.password)
assert(database.port && database.port !== '3306', 'Use an isolated non-default MySQL port')
const redisPort = Number(process.env.SCHAT_GATEWAY_REDIS_PORT)
assert(Number.isInteger(redisPort) && redisPort > 0 && redisPort <= 65535 && redisPort !== 6379)
const mysql = process.env.SCHAT_GATEWAY_MYSQL_BIN || 'mysql'
const redis = process.env.SCHAT_GATEWAY_REDIS_BIN || 'redis-cli'
const password = process.env.SCHAT_GATEWAY_MYSQL_PASSWORD || 'schat-test-only'
const directory = mkdtempSync(join(tmpdir(), 'schat-gateway-smoke-'))
const secret = randomBytes(48).toString('hex')
const children = []
const sockets = []
const tokens = []
const browserCredentials = []
const browserEnabled = process.env.SCHAT_GATEWAY_BROWSER === 'true'
const base = 'http://[::1]:34022'
const report = {
  schemaVersion: 1, startedAt: new Date().toISOString(), passed: false, checks: [],
  scope: 'local gateway/auth/chat JVMs; IPv6 client and IPv4 trusted proxy; no Nacos/file service',
  browserEnabled,
  routeConfiguration: 'existing application.yaml routes; SimpleDiscoveryClient supplies local instances'
}
let currentCheck = 'preflight'
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

function sql(statement) {
  return execFileSync(mysql, ['--no-defaults', '--protocol=TCP', '-h', database.hostname, '-P', database.port,
    '-u', 'root', '--batch', '--skip-column-names', 'schat_gateway_smoke', '-e', statement], {
    env: { ...process.env, MYSQL_PWD: password }, encoding: 'utf8', timeout: 5000
  }).trim()
}

function redisCommand(...argumentsList) {
  const environment = { ...process.env }
  delete environment.REDISCLI_AUTH
  return execFileSync(redis, ['-h', '127.0.0.1', '-p', String(redisPort), ...argumentsList], {
    env: environment, encoding: 'utf8', timeout: 5000
  }).trim()
}

async function until(name, predicate, timeout = 30000) {
  const deadline = performance.now() + timeout
  while (performance.now() < deadline) {
    if (await predicate()) return
    await delay(50)
  }
  throw new Error(`Timed out: ${name}`)
}

async function check(name, action) {
  currentCheck = name
  await action()
  report.checks.push({ name, passed: true })
  console.log(`PASS ${name}`)
}

function launch(module, port) {
  const log = join(directory, `${module}.log`)
  const output = openSync(log, 'a')
  const gateway = module === 's-gateway'
  const args = ['-Xms64m', '-Xmx256m', '-jar', join(root, module, 'target', `${module}-0.0.1-SNAPSHOT.jar`),
    `--server.port=${port}`, `--server.address=${gateway ? '::1' : '127.0.0.1'}`, '--spring.config.import=',
    '--spring.cloud.nacos.discovery.enabled=false', `--spring.cloud.discovery.enabled=${gateway}`,
    '--spring.cloud.gateway.discovery.locator.enabled=false',
    '--spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration',
    `--spring.datasource.url=${jdbc}`, '--spring.datasource.username=root', `--spring.datasource.password=${password}`,
    '--spring.data.redis.host=127.0.0.1', `--spring.data.redis.port=${redisPort}`, '--spring.data.redis.password=',
    '--spring.data.redis.connect-timeout=1s', '--spring.data.redis.timeout=2s',
    '--schat.login.trusted-proxies=127.0.0.1', '--schat.instance.id=gateway-chat:34021', '--schat.shutdown.wait-seconds=0',
    '--mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl']
  if (gateway) {
    args.push('--spring.cloud.discovery.client.simple.instances.s-user[0].uri=http://127.0.0.1:34020',
      '--spring.cloud.discovery.client.simple.instances.s-server[0].uri=http://127.0.0.1:34021')
  }
  const child = spawn('java', args, { cwd: root, stdio: ['ignore', output, output], env: {
    ...process.env, SCHAT_JWT_SECRET: secret, SCHAT_INTERNAL_SECRET: secret, MYSQL_PASSWORD: password,
    REDIS_PASSWORD: '', SCHAT_DELIVERY_ALERT_ENABLED: 'false', SCHAT_DELIVERY_OPS_ENABLED: 'false'
  } })
  closeSync(output)
  children.push(child)
  return child
}

async function ready(child, url) {
  await until('test JVM ready', async () => {
    assert(child.exitCode === null && child.signalCode === null, 'Test JVM exited during startup; inspect private logs')
    try { return (await fetch(url, { signal: AbortSignal.timeout(1500) })).ok }
    catch { return false }
  }, 90000)
}

async function request(path, { body, token, headers = {} } = {}) {
  const response = await fetch(`${base}${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
  })
  return { status: response.status, body: await response.json() }
}

async function api(path, options) {
  const response = await request(path, options)
  assert.equal(response.status, 200, 'Expected HTTP 200')
  assert.equal(response.body.code, 200, 'Expected successful business response')
  return response.body.data
}

function id(value) {
  if (typeof value === 'number') assert(Number.isSafeInteger(value))
  assert(/^\d+$/.test(String(value)), 'Expected a numeric identifier')
  return String(value)
}

async function peer(user, token = user.token) {
  const socket = new WebSocket(`ws://[::1]:34022/api/ws/chat/${id(user.user.id)}?token=${encodeURIComponent(token)}`)
  sockets.push(socket)
  const packets = []
  socket.addEventListener('message', event => packets.push(JSON.parse(event.data)))
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('WebSocket handshake timeout')), 10000)
    socket.addEventListener('open', () => { clearTimeout(timer); resolve() }, { once: true })
    socket.addEventListener('error', () => { clearTimeout(timer); reject(new Error('WebSocket handshake rejected')) }, { once: true })
  })
  return { socket, packets, send: packet => socket.send(JSON.stringify(packet)) }
}

try {
  for (const [port, host] of [[34020, '127.0.0.1'], [34021, '127.0.0.1'], [34022, '::1']]) {
    await new Promise((resolve, reject) => {
      const listener = createServer()
      listener.once('error', reject)
      listener.listen(port, host, () => listener.close(resolve))
    })
  }
  assert.equal(sql('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()'), '0')
  assert.equal(redisCommand('DBSIZE'), '0', 'Use a new empty test Redis instance')
  console.log(`Gateway artifacts: ${directory}`)
  await ready(launch('s-user', 34020), 'http://127.0.0.1:34020/v3/api-docs')
  await ready(launch('s-server', 34021), 'http://127.0.0.1:34021/v3/api-docs')
  await ready(launch('s-gateway', 34022), `${base}/s-user/v3/api-docs`)
  const users = []
  await check('gateway-auth-and-strip-prefix', async () => {
    const suffix = randomBytes(4).toString('hex')
    for (const role of ['ga', 'gb']) {
      const credentials = { username: `${role}_${suffix}`, password: 'Gateway-test-password-123' }
      browserCredentials.push(credentials)
      await delay(1100)
      await api('/api/auth/register', { body: { ...credentials, nickname: role } })
      await delay(1100)
      const user = await api('/api/auth/login', { body: credentials })
      users.push(user)
      tokens.push(user.token)
    }
  })
  const [sender, receiver] = users
  const receiverId = id(receiver.user.id)
  await check('gateway-protected-http-rejects-missing-and-invalid-tokens', async () => {
    for (const token of [undefined, 'invalid-test-token']) {
      const response = await request(`/api/chat/${receiverId}/history`, { token })
      assert.equal(response.status, 401)
      assert.equal(response.body.code, 401)
    }
  })
  await check('gateway-websocket-rejects-invalid-handshake-token', async () => {
    const invalid = await peer(sender, 'invalid-test-token')
    await until('invalid WS closed', () => invalid.socket.readyState === WebSocket.CLOSED, 10000)
    assert(!invalid.packets.some(packet => packet.type === 'MSG_ACK'))
  })
  const source = await peer(sender)
  const target = await peer(receiver)
  let messageId
  await check('gateway-websocket-route-and-durable-delivery-ack', async () => {
    source.send({ type: 'SEND_MESSAGE', data: {
      clientMsgId: 'gateway-ws', sessionId: receiverId, type: 'text', content: 'gateway-search-body'
    } })
    await until('sender WS acknowledgement', () => source.packets.some(packet => packet.type === 'MSG_ACK'))
    const ack = source.packets.find(packet => packet.type === 'MSG_ACK')
    assert.equal(typeof ack.data.messageId, 'string')
    messageId = id(ack.data.messageId)
    await until('recipient WS delivery', () => target.packets.some(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === messageId))
    const delivery = target.packets.find(packet => packet.type === 'NEW_MESSAGE' && packet.data.id === messageId)
    target.send({ msgType: 'DELIVERY_ACK', messageId, deliveryRevision: delivery.deliveryRevision || 0 })
    await until('recipient durable ACK', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${messageId} AND target_user_id=${receiverId}`) === 'DELIVERED')
  })
  await check('gateway-search-contract-and-http-size-limit', async () => {
    const results = await api('/api/chat/search?keyword=gateway-search-body', { token: sender.token })
    assert(Array.isArray(results))
    const message = results.find(result => result.messageId === messageId)
    assert(message && message.content === 'gateway-search-body' && Number.isFinite(message.createTime))
    const rejected = await request(`/api/chat/${receiverId}/messages`, {
      token: sender.token, body: { clientMsgId: 'gateway-oversized', type: 'text', content: 'x'.repeat(6000) }
    })
    assert(rejected.body.code !== 200 && rejected.body.message.includes('5000'))
    assert.equal(sql("SELECT COUNT(*) FROM tb_chat_message WHERE client_msg_id='gateway-oversized'"), '0')
  })
  await check('gateway-forwarded-ip-chain-cannot-be-forged', async () => {
    await delay(6000)
    const began = performance.now()
    const results = await Promise.all(Array.from({ length: 20 }, (_, index) => request('/api/auth/login', {
      body: { username: 'missing_gateway_user', password: 'Gateway-test-password-123' },
      headers: { 'X-Forwarded-For': `198.51.100.${index}`, 'X-Real-IP': `203.0.113.${index}` }
    })))
    const allowed = results.filter(result => result.status !== 429).length
    assert(allowed < 20 && allowed <= 5 + Math.ceil((performance.now() - began) / 1000))
    assert(results.every(result => [200, 429].includes(result.status) && result.body.code !== 200))
    assert.equal(redisCommand('EXISTS', 'schat:rate:login:0:0:0:0:0:0:0:1'), '1', 'The real IPv6 client IP must be used')
    assert.equal(redisCommand('EXISTS', 'schat:rate:login:127.0.0.1'), '0', 'The trusted proxy IP must not replace the client IP')
    assert.equal(redisCommand('EXISTS', ...Array.from({ length: 20 }, (_, index) => `schat:rate:login:198.51.100.${index}`)), '0')
    report.loginBurst = { checked: allowed, limited: 20 - allowed }
  })
  await check('gateway-logs-do-not-contain-websocket-bearer-tokens', async () => {
    const gatewayLog = readFileSync(join(directory, 's-gateway.log'), 'utf8')
    assert(!tokens.some(token => gatewayLog.includes(token)), 'Gateway logs contain a live test token')
  })
  if (browserEnabled) {
    await delay(6000)
    const { runBrowserChecks } = await import('./lib/browser-checks.mjs')
    report.browser = await runBrowserChecks({ root, directory, check, sql, users, credentials: browserCredentials })
  }
  report.passed = true
} catch (error) {
  report.failedCheck = currentCheck
  report.error = error.name
  console.error(`Gateway acceptance failed during ${currentCheck}; inspect private logs`)
  process.exitCode = 1
} finally {
  for (const socket of sockets) { try { socket.close() } catch {} }
  for (const child of children.reverse()) {
    if (child.exitCode !== null || child.signalCode !== null) continue
    child.kill('SIGTERM')
    try { await until('test JVM exit', () => child.exitCode !== null || child.signalCode !== null, 10000) }
    catch { child.kill('SIGKILL'); await until('test JVM killed', () => child.exitCode !== null || child.signalCode !== null, 5000) }
  }
  report.finishedAt = new Date().toISOString()
  writeFileSync(join(directory, 'report.json'), JSON.stringify(report, null, 2) + '\n')
  console.log(`Gateway report: ${join(directory, 'report.json')}`)
  if (report.passed) console.log('GATEWAY SMOKE PASSED')
}
