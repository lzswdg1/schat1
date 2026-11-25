/**
 * Axios 封装
 * 统一请求/响应拦截，自动携带 Token，401 自动清除登录态
 */

import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
  // 自定义 JSON 解析：将已知 ID 字段的超长整数转为字符串，防止雪花 ID 精度丢失
  transformResponse: [(data) => {
    if (typeof data !== 'string') return data
    try {
      // 只转换已知的 ID 字段（id, messageId, fromUserId, senderId, targetId, cursor 等）
      const safe = data.replace(/"(id|messageId|fromUserId|senderId|receiverId|targetId|cursor|applyId|groupId|ownerId|userId|friendId|seq|quoteMsgId)"\s*:\s*(\d{16,})/g,
        '"$1":"$2"')
      return JSON.parse(safe)
    } catch {
      try { return JSON.parse(data) } catch { return data }
    }
  }]
})

function clearAuthState() {
  localStorage.removeItem('auth')
  localStorage.removeItem('session')
  localStorage.removeItem('contact')
  localStorage.removeItem('chat_v2')
}

// 请求拦截：自动携带 Token
http.interceptors.request.use(
  (config) => {
    // 从 localStorage 读取 pinia 持久化的 auth store
    try {
      const authRaw = localStorage.getItem('auth')
      if (authRaw) {
        const authState = JSON.parse(authRaw)
        if (authState.token) {
          config.headers.Authorization = `Bearer ${authState.token}`
        }
      }
    } catch {
      // ignore
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截：401 自动清除登录态并跳转登录页
http.interceptors.response.use(
  (response) => {
    const payload = response.data
    if (payload?.code === 401) {
      clearAuthState()
      window.location.href = '/login'
      return Promise.reject(new Error(payload.message || '登录已过期，请重新登录'))
    }
    return payload
  },
  (error) => {
    if (error.response?.status === 401) {
      clearAuthState()
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default http

/**
 * 补全后端返回的文件路径前缀
 * 后端返回 /file/xxx.jpg，前端需要 /api/file/xxx.jpg 才能走 Vite 代理 + 鉴权
 */
export function normalizeFileUrl(url) {
  if (!url) return ''
  if (url.startsWith('/api/')) return url
  if (url.startsWith('/file/')) return `/api${url}`
  if (url.startsWith('http://') || url.startsWith('https://')) return url
  return url
}
