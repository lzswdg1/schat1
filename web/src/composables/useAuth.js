/**
 * 认证逻辑组合函数
 * 封装登录、注册、登出逻辑，提供 loading 和错误状态管理
 */

import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'

export function useAuth() {
  const router = useRouter()
  const authStore = useAuthStore()

  const loading = ref(false)
  const error = ref('')

  async function login(username, password) {
    error.value = ''
    loading.value = true
    try {
      const res = await authStore.login(username, password)
      if (res.code === 200) {
        router.push('/')
      } else {
        error.value = res.message || '登录失败'
      }
      return res
    } catch {
      error.value = '登录失败，请重试'
    } finally {
      loading.value = false
    }
  }

  async function register(username, password, nickname) {
    error.value = ''
    loading.value = true
    try {
      const res = await authStore.register(username, password, nickname)
      if (res.code === 200) {
        router.push('/login')
      } else {
        error.value = res.message || '注册失败'
      }
      return res
    } catch {
      error.value = '注册失败，请重试'
    } finally {
      loading.value = false
    }
  }

  function logout() {
    authStore.logout()
    router.push('/login')
  }

  return {
    loading,
    error,
    login,
    register,
    logout
  }
}
