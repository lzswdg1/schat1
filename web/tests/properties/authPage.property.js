/**
 * 认证页面属性测试
 * Property 3
 */

import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import RegisterView from '@/views/RegisterView.vue'

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/register', component: RegisterView },
      { path: '/login', component: { template: '<div>login</div>' } },
      { path: '/', component: { template: '<div>home</div>' } }
    ]
  })
}

describe('Auth Page Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Feature: web-im-system, Property 3: 注册密码不匹配校验
  it('mismatched passwords should show error and prevent submission', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.stringMatching(/[A-Za-z0-9_]{1,20}/),
        fc.string({ minLength: 8, maxLength: 20 }).filter(v => v.trim().length >= 8),
        fc.string({ minLength: 8, maxLength: 20 }).filter(v => v.trim().length >= 8),
        async (username, password, confirmPassword) => {
          // Only test when passwords don't match
          fc.pre(password !== confirmPassword)

          const router = createTestRouter()
          await router.push('/register')
          await router.isReady()

          const wrapper = mount(RegisterView, {
            global: {
              plugins: [createPinia(), router]
            }
          })

          // Fill in form (4 inputs: username, nickname, password, confirmPassword)
          const inputs = wrapper.findAll('input')
          await inputs[0].setValue(username)    // accountId
          await inputs[1].setValue(username)    // nickname
          await inputs[2].setValue(password)
          await inputs[3].setValue(confirmPassword)

          // Submit
          await wrapper.find('form').trigger('submit')

          // Should show error
          const errorEl = wrapper.find('.error-msg')
          expect(errorEl.exists()).toBe(true)
          expect(errorEl.text()).toContain('密码不匹配')

          wrapper.unmount()
        }
      ),
      { numRuns: 10 }
    )
  })
})
