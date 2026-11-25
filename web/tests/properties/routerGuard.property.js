/**
 * 路由守卫属性测试
 * Property 2
 */

import { describe, it, expect, beforeEach } from 'vitest'
import fc from 'fast-check'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'

function createTestRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/login', name: 'Login', component: { template: '<div>login</div>' } },
      { path: '/register', name: 'Register', component: { template: '<div>register</div>' } },
      { path: '/', name: 'Home', component: { template: '<div>home</div>' } }
    ]
  })

  router.beforeEach((to, from, next) => {
    const authStore = useAuthStore()
    const isLoggedIn = authStore.isLoggedIn

    if (isLoggedIn && (to.path === '/login' || to.path === '/register')) {
      next('/')
    } else if (!isLoggedIn && to.path === '/') {
      next('/login')
    } else {
      next()
    }
  })

  return router
}

describe('Router Guard Properties', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Feature: web-im-system, Property 2: 路由守卫正确性
  it('logged-in user visiting /login or /register should be redirected to /', async () => {
    await fc.assert(
      fc.asyncProperty(
        fc.constantFrom('/login', '/register'),
        async (path) => {
          setActivePinia(createPinia())
          const authStore = useAuthStore()
          // Simulate logged in
          authStore.user = { id: 'user_1', username: 'test', nickname: 'Test', avatar: '' }
          authStore.token = 'fake_token'

          const router = createTestRouter()
          await router.push(path)
          await router.isReady()

          expect(router.currentRoute.value.path).toBe('/')
        }
      ),
      { numRuns: 10 }
    )
  })

  // Feature: web-im-system, Property 2: 未登录访问 / 重定向至 /login
  it('unauthenticated user visiting / should be redirected to /login', async () => {
    const authStore = useAuthStore()
    expect(authStore.token).toBeNull()

    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    expect(router.currentRoute.value.path).toBe('/login')
  })
})
