/**
 * 认证相关 Mock 实现
 */

import { mockDB, persistDB } from './data.js'
import { delay, mockResponse } from './helpers.js'

export async function login(username, password) {
  await delay()

  const storedPassword = mockDB.passwords.get(username)
  if (!storedPassword || storedPassword !== password) {
    return mockResponse(null, 401, '用户名或密码错误')
  }

  let user = null
  for (const [, u] of mockDB.users) {
    if (u.username === username) {
      user = u
      break
    }
  }

  if (!user) return mockResponse(null, 401, '用户名或密码错误')

  const token = `token_${user.id}_${Date.now()}`
  mockDB.tokens.set(token, user.id)
  persistDB()

  return mockResponse({ user: { ...user }, token })
}

export async function register(username, password, nickname) {
  await delay()

  if (mockDB.passwords.has(username)) {
    return mockResponse(null, 400, '用户名已被注册')
  }

  if (!password || password.length < 6) {
    return mockResponse(null, 400, '密码长度不能少于6位')
  }

  const userId = `user_${Date.now()}`
  const newUser = {
    id: userId,
    username,
    nickname: nickname || username,
    avatar: `https://api.dicebear.com/7.x/avataaars/svg?seed=${username}`
  }

  mockDB.users.set(userId, newUser)
  mockDB.passwords.set(username, password)
  mockDB.friends.set(userId, [])
  mockDB.sessions.set(userId, [])
  mockDB.friendRequests.set(userId, [])
  persistDB()

  return mockResponse({ user: { ...newUser } })
}

export async function logout(token) {
  await delay()

  if (token) {
    mockDB.tokens.delete(token)
    persistDB()
  }

  return mockResponse({ success: true })
}
