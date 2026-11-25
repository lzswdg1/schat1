import assert from 'node:assert/strict'
import { createConnection, createServer } from 'node:net'
import { once } from 'node:events'
import { test } from 'node:test'
import { TcpFaultProxy } from '../lib/tcp-fault-proxy.mjs'

async function fixture(context) {
  const sockets = new Set()
  const server = createServer(socket => {
    sockets.add(socket)
    socket.on('error', () => socket.destroy())
    socket.on('close', () => sockets.delete(socket))
    socket.pipe(socket)
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const proxy = await new TcpFaultProxy(server.address().port).listen()
  context.after(async () => {
    await proxy.close()
    for (const socket of sockets) socket.destroy()
    await new Promise(resolve => server.close(resolve))
  })
  return proxy
}

async function connect(proxy, context) {
  const socket = createConnection({ host: '127.0.0.1', port: proxy.port })
  socket.on('error', () => socket.destroy())
  context.after(() => socket.destroy())
  await once(socket, 'connect')
  return socket
}

async function roundtrip(socket, payload) {
  const received = once(socket, 'data')
  socket.write(payload)
  assert.equal((await received)[0].toString(), payload)
}

test('forwards multiple concurrent TCP connections without changing bytes', { timeout: 5000 }, async context => {
  const proxy = await fixture(context)
  const first = await connect(proxy, context)
  const second = await connect(proxy, context)
  await Promise.all([roundtrip(first, 'first'), roundtrip(second, '第二条消息')])
  assert.equal(proxy.stats.forwardedConnections, 2)
  assert.equal(proxy.stats.activeConnections, 2)
})

test('cut disconnects existing clients and rejects new connections until restored', { timeout: 5000 }, async context => {
  const proxy = await fixture(context)
  const original = await connect(proxy, context)
  await roundtrip(original, 'before cut')
  const closed = once(original, 'close')
  proxy.cut()
  await closed
  assert.equal(proxy.stats.forcedDisconnects, 1)
  assert.equal(proxy.stats.activeConnections, 0)
  const refused = createConnection({ host: '127.0.0.1', port: proxy.port })
  refused.on('error', () => {})
  context.after(() => refused.destroy())
  await new Promise(resolve => refused.once('close', resolve))
  assert.equal(proxy.stats.rejectedConnections, 1)
  proxy.restore()
  await roundtrip(await connect(proxy, context), 'after recovery')
  assert.equal(proxy.stats.forwardedConnections, 2)
})

test('close drains sockets and is safe to repeat', { timeout: 5000 }, async context => {
  const proxy = await fixture(context)
  const client = await connect(proxy, context)
  await roundtrip(client, 'active')
  const closed = once(client, 'close')
  await proxy.close()
  await closed
  await proxy.close()
  assert.equal(proxy.stats.activeConnections, 0)
  assert.throws(() => proxy.restore(), /closed/)
  assert.throws(() => proxy.cut(), /closed/)
})

test('handles an unavailable upstream without unhandled socket errors', { timeout: 5000 }, async context => {
  const unused = createServer()
  await new Promise(resolve => unused.listen(0, '127.0.0.1', resolve))
  const targetPort = unused.address().port
  await new Promise(resolve => unused.close(resolve))
  const proxy = await new TcpFaultProxy(targetPort).listen()
  context.after(() => proxy.close())
  const client = createConnection({ host: '127.0.0.1', port: proxy.port })
  client.on('error', () => {})
  context.after(() => client.destroy())
  await new Promise(resolve => client.once('close', resolve))
  assert.equal(proxy.stats.activeConnections, 0)
  assert.equal(proxy.stats.forwardedConnections, 0)
})

test('validates ports and only binds loopback', { timeout: 5000 }, async context => {
  for (const port of [0, -1, 65536, '6379', 1.5]) assert.throws(() => new TcpFaultProxy(port), RangeError)
  const proxy = await fixture(context)
  await assert.rejects(proxy.listen(), /twice/)
  const invalid = new TcpFaultProxy(6379)
  context.after(() => invalid.close())
  await assert.rejects(invalid.listen(-1), RangeError)
})
