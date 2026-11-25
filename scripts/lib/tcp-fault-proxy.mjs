import { createConnection, createServer } from 'node:net'

export class TcpFaultProxy {
  #targetPort
  #server
  #connections = new Map()
  #blocked = false
  #closed = false
  #stats = { forwardedConnections: 0, rejectedConnections: 0, forcedDisconnects: 0 }

  constructor(targetPort) {
    if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) {
      throw new RangeError('Target port must be an integer from 1 to 65535')
    }
    this.#targetPort = targetPort
  }

  async listen(port = 0) {
    if (this.#server || this.#closed) throw new Error('Proxy cannot be started twice')
    if (!Number.isInteger(port) || port < 0 || port > 65535) throw new RangeError('Invalid listen port')
    this.#server = createServer(incoming => {
      incoming.on('error', () => incoming.destroy())
      if (this.#blocked || this.#closed) {
        this.#stats.rejectedConnections++
        incoming.destroy()
        return
      }
      const upstream = createConnection({ host: '127.0.0.1', port: this.#targetPort })
      this.#connections.set(incoming, upstream)
      const dispose = () => {
        this.#connections.delete(incoming)
        incoming.destroy()
        upstream.destroy()
      }
      incoming.on('close', dispose)
      upstream.on('close', dispose)
      upstream.on('error', dispose)
      upstream.on('connect', () => {
        if (this.#blocked || this.#closed || incoming.destroyed) {
          dispose()
          return
        }
        this.#stats.forwardedConnections++
        incoming.pipe(upstream)
        upstream.pipe(incoming)
      })
    })
    await new Promise((resolve, reject) => {
      this.#server.once('error', reject)
      this.#server.listen(port, '127.0.0.1', () => {
        this.#server.off('error', reject)
        resolve()
      })
    })
    return this
  }

  get port() {
    const address = this.#server?.address()
    if (!address || typeof address === 'string') throw new Error('Proxy is not listening')
    return address.port
  }

  get stats() {
    return { ...this.#stats, activeConnections: this.#connections.size, blocked: this.#blocked }
  }

  cut() {
    if (this.#closed) throw new Error('Proxy is closed')
    this.#blocked = true
    this.#stats.forcedDisconnects += this.#connections.size
    this.#disconnect()
  }

  restore() {
    if (this.#closed) throw new Error('Proxy is closed')
    this.#blocked = false
  }

  async close() {
    this.#closed = true
    this.#blocked = true
    this.#disconnect()
    if (this.#server?.listening) {
      await new Promise((resolve, reject) => this.#server.close(error => error ? reject(error) : resolve()))
    }
  }

  #disconnect() {
    for (const [incoming, upstream] of this.#connections) {
      incoming.destroy()
      upstream.destroy()
    }
    this.#connections.clear()
  }
}
