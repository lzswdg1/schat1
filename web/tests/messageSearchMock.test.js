import { describe, expect, it } from 'vitest'
import { mockDB, resetDB } from '@/api/mock/data.js'

describe('mock message search response shape', () => {
  it('returns the same data array contract for empty and non-empty searches', async () => {
    resetDB()
    mockDB.messages.set('contract', [{
      id: 'mock-message', type: 'text', senderId: 'mock-user', content: 'needle mock', timestamp: 1
    }])
    const { searchMessages } = await import('@/api/mock/chat.mock.js')
    const empty = await searchMessages('')
    const result = await searchMessages('needle')
    expect(empty).toMatchObject({ code: 200, data: [] })
    expect(Array.isArray(result.data)).toBe(true)
    expect(result.data).toHaveLength(1)
    expect(result.data[0]).toMatchObject({ messageId: 'mock-message', content: 'needle mock' })
  })
})
