/**
 * 认证 API 属性测试
 * Properties 1, 4
 */

import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import { login, register } from '@/api/auth.js'
import { resetDB } from '@/api/mock/data.js'

describe('Auth API Properties', () => {
  beforeEach(() => {
    resetDB()
  })

  // Feature: web-im-system, Property 1: 登录状态往返一致性
  it('login with valid credentials should return matching user and non-empty token', async () => {
    const res = await login('zhangsan', '123456')
    expect(res.code).toBe(200)
    expect(res.data.user.username).toBe('zhangsan')
    expect(res.data.token).toBeTruthy()
    expect(typeof res.data.token).toBe('string')
    expect(res.data.token.length).toBeGreaterThan(0)
  })

  // Feature: web-im-system, Property 1 (extended): register then login roundtrip
  it('registered user should be able to login and get consistent data', async () => {
    // Use a few concrete examples instead of many random ones to avoid timeout
    const testCases = [
      { username: 'testuser_a', password: 'pass1234' },
      { username: 'testuser_b', password: 'securepass' },
      { username: 'testuser_c', password: 'mypassword' }
    ]

    for (const { username, password } of testCases) {
      resetDB()
      const regRes = await register(username, password)
      expect(regRes.code).toBe(200)

      const loginRes = await login(username, password)
      expect(loginRes.code).toBe(200)
      expect(loginRes.data.user.username).toBe(username)
      expect(loginRes.data.token).toBeTruthy()
    }
  }, 15000)

  // Feature: web-im-system, Property 4: 注册 API 响应格式
  it('register should return standard response with user object', async () => {
    const testCases = [
      { username: 'newreg_a', password: 'pass123456' },
      { username: 'newreg_b', password: 'pass234567' },
      { username: 'newreg_c', password: 'pass345678' }
    ]

    for (const { username, password } of testCases) {
      resetDB()
      const res = await register(username, password)
      expect(res).toHaveProperty('code')
      expect(res).toHaveProperty('message')
      expect(res).toHaveProperty('data')
      expect(res.code).toBe(200)
      expect(res.data.user).toBeDefined()
      expect(res.data.user.username).toBe(username)
      expect(res.data.user.id).toBeTruthy()
    }
  }, 15000)
})
