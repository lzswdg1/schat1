/**
 * API Mock 工具层属性测试
 * Properties 23, 24
 */

import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { mockResponse, delay } from '@/api/mock/helpers.js'

describe('API Mock Helpers Properties', () => {
  // Feature: web-im-system, Property 23: API 响应格式一致性
  it('mockResponse should always return object with code, message, data fields', () => {
    fc.assert(
      fc.property(
        fc.anything(),
        fc.integer({ min: 100, max: 599 }),
        fc.string({ minLength: 1 }),
        (data, code, message) => {
          const res = mockResponse(data, code, message)
          expect(res).toHaveProperty('code', code)
          expect(res).toHaveProperty('message', message)
          expect(res).toHaveProperty('data', data)
        }
      ),
      { numRuns: 100 }
    )
  })

  // Feature: web-im-system, Property 23 (defaults): mockResponse defaults
  it('mockResponse should default to code=200 and message="ok"', () => {
    fc.assert(
      fc.property(
        fc.anything(),
        (data) => {
          const res = mockResponse(data)
          expect(res.code).toBe(200)
          expect(res.message).toBe('ok')
          expect(res.data).toBe(data)
        }
      ),
      { numRuns: 100 }
    )
  })

  // Feature: web-im-system, Property 24: API 模拟延迟范围
  it('delay should resolve within 200ms-500ms range', async () => {
    // Run 5 iterations (async timing tests are slow)
    for (let i = 0; i < 5; i++) {
      const start = performance.now()
      await delay()
      const elapsed = performance.now() - start
      // Allow small tolerance for timer imprecision
      expect(elapsed).toBeGreaterThanOrEqual(190)
      expect(elapsed).toBeLessThanOrEqual(600)
    }
  })
})
