import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import { randomBytes } from 'node:crypto'

const require = createRequire(import.meta.url)
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds))

async function until(name, predicate, timeout = 30000) {
  const deadline = performance.now() + timeout
  while (performance.now() < deadline) {
    if (await predicate()) return
    await delay(50)
  }
  throw new Error(`Timed out: ${name}`)
}

async function cache(page, operation, key, value) {
  return page.evaluate(({ operation, key, value }) => new Promise((resolve, reject) => {
    const open = indexedDB.open('im_chat')
    open.onerror = () => reject(new Error('IndexedDB open failed'))
    open.onsuccess = () => {
      const database = open.result
      const transaction = database.transaction('messages', operation === 'get' ? 'readonly' : 'readwrite')
      const store = transaction.objectStore('messages')
      const request = operation === 'get' ? store.get(key) : operation === 'delete' ? store.delete(key) : store.put(value)
      let result
      request.onsuccess = () => { result = request.result }
      transaction.oncomplete = () => { database.close(); resolve(result) }
      transaction.onerror = () => { database.close(); reject(new Error('IndexedDB transaction failed')) }
    }
  }), { operation, key, value })
}

export async function runBrowserChecks({ root, directory, check, sql, users, credentials }) {
  const { chromium } = require('playwright')
  const { createServer } = await import(pathToFileURL(join(root, 'web/node_modules/vite/dist/node/index.js')).href)
  const server = await createServer({
    root: join(root, 'web'), configFile: join(root, 'web/vite.config.js'), envFile: false,
    define: { 'import.meta.env.VITE_USE_MOCK': JSON.stringify('false') },
    server: { host: '127.0.0.1', port: 34123, strictPort: true, proxy: {
      '/api/ws': { target: 'http://[::1]:34022', ws: true, changeOrigin: true },
      '/api': { target: 'http://[::1]:34022', changeOrigin: true }
    } }
  })
  let browser
  let pageA
  let pageB
  const pageErrors = []
  const summary = { engine: 'Chromium', freshProfiles: true, gatewayUsed: true, externalRequestsBlocked: 0 }
  const marker = `browser-${randomBytes(4).toString('hex')}`
  const content = `${marker} <img src=invalid onerror="window.__browserXss=1">`
  const senderId = String(users[0].user.id)
  const receiverId = String(users[1].user.id)
  let messageId
  try {
    await server.listen()
    browser = await chromium.launch({ headless: true })
    summary.version = browser.version()
    const contexts = []
    for (let index = 0; index < 3; index++) {
      const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, reducedMotion: 'reduce' })
      await context.route('**/*', route => {
        const url = new URL(route.request().url())
        if (url.hostname === '127.0.0.1' && url.port === '34123') return route.continue()
        summary.externalRequestsBlocked++
        return route.abort()
      })
      contexts.push(context)
    }
    pageA = await contexts[0].newPage()
    pageB = await contexts[1].newPage()
    for (const page of [pageA, pageB]) {
      page.setDefaultTimeout(15000)
      page.on('pageerror', error => pageErrors.push(error.name))
    }
    await check('browser-form-login-websocket-message-and-real-indexeddb-ack', async () => {
      for (const [index, page] of [pageA, pageB].entries()) {
        await page.goto('http://127.0.0.1:34123/login')
        await page.getByPlaceholder('请输入用户名').fill(credentials[index].username)
        await page.getByPlaceholder('请输入密码').fill(credentials[index].password)
        await page.getByRole('button', { name: '登录', exact: true }).click()
        await page.locator('.session-item').first().waitFor()
        await page.locator('.session-item').first().click()
        await page.getByPlaceholder('输入消息...').waitFor()
      }
      await pageA.getByPlaceholder('输入消息...').fill(content)
      await pageA.getByRole('button', { name: '发送', exact: true }).click()
      await until('UI send persisted by server', () => {
        messageId = sql(`SELECT message_id FROM tb_chat_message WHERE sender_id=${senderId} AND client_msg_id LIKE 'cmsg_%' ORDER BY message_id DESC LIMIT 1`)
        return /^\d+$/.test(messageId)
      })
      await until('real browser persists recipient message', async () => (await cache(pageB, 'get', [senderId, messageId]))?.content === content)
      await until('browser durability ACK reached MySQL', () => sql(`SELECT status FROM tb_message_outbox WHERE message_id=${messageId} AND target_user_id=${receiverId}`) === 'DELIVERED')
      assert.equal(await pageB.locator('.message-bubble').filter({ hasText: marker }).count(), 1)
    })
    const searchInput = () => pageB.getByPlaceholder('搜索聊天记录')
    await check('browser-local-search-does-not-execute-message-html', async () => {
      await pageB.getByTitle('搜索添加好友').click()
      await pageB.getByRole('button', { name: '搜消息', exact: true }).click()
      await searchInput().fill(marker)
      await pageB.locator('.search-result-item__content').waitFor()
      assert.equal(await pageB.locator('.search-result-item__content').textContent(), content)
      assert.equal(await pageB.locator('.search-result-item__content img, .search-result-item__content [onerror]').count(), 0)
      assert.notEqual(await pageB.evaluate(() => window.__browserXss), 1)
    })
    await check('browser-remote-search-uses-real-gateway-contract', async () => {
      await cache(pageB, 'delete', [senderId, messageId])
      await searchInput().fill('')
      const response = pageB.waitForResponse(response => new URL(response.url()).pathname === '/api/chat/search'
        && new URL(response.url()).searchParams.get('keyword') === marker)
      await searchInput().fill(marker)
      const payload = await (await response).json()
      assert(Array.isArray(payload.data))
      const result = payload.data.find(result => result.messageId === messageId)
      assert(result && result.content === content && typeof result.fromId === 'string' && Number.isFinite(result.createTime))
      await pageB.locator('.search-result-item__content').waitFor()
      assert.equal(await pageB.locator('.search-result-item__content').textContent(), content)
      assert.equal(await pageB.locator('.search-result-item__content img').count(), 0)
      assert.notEqual(await pageB.evaluate(() => window.__browserXss), 1)
      await pageB.screenshot({ path: join(directory, 'browser-search.png') })
    })
    await check('browser-delayed-real-history-cannot-resurrect-recalled-content', async () => {
      const receivedBubble = pageB.locator('.message-bubble').filter({ hasText: marker })
      await receivedBubble.hover()
      await receivedBubble.getByTitle('引用', { exact: true }).click()
      assert((await pageB.locator('.message-input__quote').textContent()).includes(marker))
      const pattern = `**/api/chat/${senderId}/history*`
      let release
      let captured = false
      let failed = false
      const held = new Promise(resolve => { release = resolve })
      const handler = async route => {
        try {
          const response = await route.fetch()
          const payload = await response.json()
          assert(payload.data.messages.some(message => message.id === messageId && message.content === content && !message.recalled))
          captured = true
          await held
          await route.fulfill({ response })
        } catch {
          failed = true
          await route.abort()
        }
      }
      await pageB.route(pattern, handler)
      try {
        await pageB.evaluate(async sessionId => {
          const { useChatStore } = await import('/src/stores/chat.js')
          window.__historyDone = false
          window.__historyFailed = false
          useChatStore().fetchHistory(sessionId).then(() => { window.__historyDone = true })
            .catch(() => { window.__historyFailed = true })
        }, senderId)
        await until('real old history response captured', () => {
          assert(!failed)
          return captured
        })
        const bubble = pageA.locator('.message-bubble--self').filter({ hasText: marker })
        await bubble.hover()
        await bubble.getByTitle('撤回', { exact: true }).click()
        await until('real browser stores recall tombstone', async () => (await cache(pageB, 'get', [senderId, messageId]))?.recalled === true)
        release()
        await pageB.waitForFunction(() => window.__historyDone || window.__historyFailed)
        assert.equal(await pageB.evaluate(() => window.__historyFailed), false)
        const row = await cache(pageB, 'get', [senderId, messageId])
        assert(row.recalled && row.content === '' && row.deliveryRevision >= 1)
        assert(!(await pageB.locator('.message-list').textContent()).includes(marker))
        await pageB.screenshot({ path: join(directory, 'browser-recall.png') })
      } finally {
        release()
        await pageB.unroute(pattern, handler)
      }
    })
    await check('browser-sidebar-preview-does-not-retain-recalled-body', async () => {
      for (const page of [pageA, pageB]) {
        assert(!(await page.locator('.session-item__message').first().textContent()).includes(marker), 'Recall left the original body in sidebar preview')
      }
    })
    await check('browser-open-search-removes-recalled-result', async () => {
      assert.equal(await pageB.locator('.search-result-item').count(), 0)
      assert(!(await pageB.locator('.message-search').textContent()).includes(marker))
    })
    await check('browser-recall-clears-selected-quote', async () => {
      assert.equal(await pageB.locator('.message-input__quote').count(), 0)
    })
    await check('browser-reload-keeps-recall-tombstone', async () => {
      await pageB.reload()
      await pageB.locator('.session-item').first().waitFor()
      await pageB.locator('.session-item').first().click()
      await until('recall still persisted after reload', async () => (await cache(pageB, 'get', [senderId, messageId]))?.recalled === true)
      assert(!(await pageB.locator('.message-list').textContent()).includes(marker))
      assert.equal(await pageB.locator('.message-bubble--recalled').count(), 1)
      assert(!(await pageB.locator('.session-item__message').first().textContent()).includes(marker))
    })
    await check('browser-native-indexeddb-v2-upgrade-and-bounded-page-read', async () => {
      const page = await contexts[2].newPage()
      await page.goto('http://127.0.0.1:34123/login')
      const observed = await page.evaluate(async () => {
        await new Promise((resolve, reject) => {
          const request = indexedDB.open('im_chat', 20)
          request.onupgradeneeded = () => {
            const database = request.result
            const messages = database.createObjectStore('messages', { keyPath: ['sessionId', 'id'] })
            for (const key of ['sessionId', 'clientMsgId', 'timestamp']) messages.createIndex(key, key)
            database.createObjectStore('cursors', { keyPath: 'sessionId' })
            database.createObjectStore('syncCursors', { keyPath: 'sessionId' })
          }
          request.onerror = () => reject(new Error('Legacy database setup failed'))
          request.onsuccess = () => {
            const database = request.result
            const transaction = database.transaction(['messages', 'cursors', 'syncCursors'], 'readwrite')
            transaction.objectStore('messages').put({ sessionId: 'legacy', id: '1', content: 'retained' })
            transaction.objectStore('syncCursors').put({ sessionId: 'legacy', cursor: { id: '1', seq: '7' } })
            transaction.oncomplete = () => { database.close(); resolve() }
            transaction.onerror = () => reject(new Error('Legacy fixture transaction failed'))
          }
        })
        const storage = await import('/src/db/chatDB.js')
        await storage.default.open()
        const retained = (await storage.getRecentMessages('legacy'))[0]
        const checkpoint = await storage.getSyncCursor('legacy')
        await storage.putMessages(Array.from({ length: 5000 }, (_, index) => ({
          sessionId: 'bulk', id: String(index + 1), timestamp: index, content: 'synthetic browser fixture'
        })))
        let decoded = 0
        const reading = message => { decoded++; return message }
        storage.default.messages.hook('reading', reading)
        let page
        try { page = await storage.getRecentMessages('bulk', 50) }
        finally { storage.default.messages.hook('reading').unsubscribe(reading) }
        return { version: storage.default.verno, retained, checkpoint, decoded, returned: page.length }
      })
      assert.equal(observed.version, 3)
      assert.equal(observed.retained.content, 'retained')
      assert.equal(observed.retained.timestamp, 0)
      assert.deepEqual(observed.checkpoint, { id: '1', seq: '7' })
      assert.equal(observed.decoded, 50)
      assert.equal(observed.returned, 50)
      summary.indexedDb = { upgradedFrom: 2, upgradedTo: 3, seeded: 5000, decoded: observed.decoded, returned: observed.returned }
    })
    await check('browser-logout-clears-local-message-cache', async () => {
      await pageB.getByRole('button', { name: '退出登录', exact: true }).click()
      await pageB.getByPlaceholder('请输入用户名').waitFor()
      const count = await pageB.evaluate(async () => {
        const storage = await import('/src/db/chatDB.js')
        return storage.default.messages.count()
      })
      assert.equal(count, 0)
    })
    assert.deepEqual(pageErrors, [], 'Unexpected uncaught browser errors')
    return summary
  } catch (error) {
    if (pageB && !pageB.isClosed()) await pageB.screenshot({ path: join(directory, 'browser-failure.png') }).catch(() => {})
    throw error
  } finally {
    if (browser) await browser.close()
    await server.close()
  }
}
