/**
 * MessageListener 属性测试
 * Property 25
 */

import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import { messageListener } from '@/api/mock/listener.js'

describe('MessageListener Properties', () => {
  // Feature: web-im-system, Property 25: EventBus 事件往返
  it('on should register callback, emit should trigger it, off should remove it', () => {
    fc.assert(
      fc.property(
        fc.string({ minLength: 1, maxLength: 20 }),
        fc.anything(),
        (eventName, data) => {
          let callCount = 0
          let receivedData = undefined

          const callback = (d) => {
            callCount++
            receivedData = d
          }

          // Register and emit
          messageListener.on(eventName, callback)
          messageListener.emit(eventName, data)
          expect(callCount).toBe(1)
          expect(receivedData).toBe(data)

          // Remove and emit again
          messageListener.off(eventName, callback)
          messageListener.emit(eventName, data)
          expect(callCount).toBe(1) // should not increase

          // Clean up any leftover events
          messageListener._events.delete(eventName)
        }
      ),
      { numRuns: 50 }
    )
  })

  // Feature: web-im-system, Property 25 (multiple listeners)
  it('multiple listeners on same event should all be triggered', () => {
    const event = 'test-multi'
    let count1 = 0
    let count2 = 0
    const cb1 = () => count1++
    const cb2 = () => count2++

    messageListener.on(event, cb1)
    messageListener.on(event, cb2)
    messageListener.emit(event, null)

    expect(count1).toBe(1)
    expect(count2).toBe(1)

    // Remove one, other should still work
    messageListener.off(event, cb1)
    messageListener.emit(event, null)

    expect(count1).toBe(1)
    expect(count2).toBe(2)

    // Cleanup
    messageListener.off(event, cb2)
  })
})
