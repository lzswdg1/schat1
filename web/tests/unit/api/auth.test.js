import { describe, it, expect, beforeEach } from 'vitest'
import { login, register } from '@/api/auth.js'
import { resetDB } from '@/api/mock/data.js'

describe('Auth API', () => {
  beforeEach(() => {
    resetDB()
  })

  describe('login', () => {
    it('should return user and token on valid credentials', async () => {
      const res = await login('zhangsan', '123456')
      expect(res.code).toBe(200)
      expect(res.message).toBe('ok')
      expect(res.data.user).toBeDefined()
      expect(res.data.user.username).toBe('zhangsan')
      expect(res.data.token).toBeTruthy()
    })

    it('should return 401 on wrong password', async () => {
      const res = await login('zhangsan', 'wrongpassword')
      expect(res.code).toBe(401)
      expect(res.message).toBe('用户名或密码错误')
      expect(res.data).toBeNull()
    })

    it('should return 401 on non-existent username', async () => {
      const res = await login('nonexistent', '123456')
      expect(res.code).toBe(401)
      expect(res.message).toBe('用户名或密码错误')
      expect(res.data).toBeNull()
    })
  })

  describe('register', () => {
    it('should create a new user and return user object', async () => {
      const res = await register('newuser', 'password123')
      expect(res.code).toBe(200)
      expect(res.message).toBe('ok')
      expect(res.data.user).toBeDefined()
      expect(res.data.user.username).toBe('newuser')
      expect(res.data.user.id).toBeTruthy()
      expect(res.data.user.nickname).toBe('newuser')
    })

    it('should return 400 when username already exists', async () => {
      const res = await register('zhangsan', 'password123')
      expect(res.code).toBe(400)
      expect(res.message).toBe('用户名已被注册')
      expect(res.data).toBeNull()
    })

    it('should allow login with newly registered credentials', async () => {
      await register('testuser', 'testpass')
      const res = await login('testuser', 'testpass')
      expect(res.code).toBe(200)
      expect(res.data.user.username).toBe('testuser')
    })
  })
})
