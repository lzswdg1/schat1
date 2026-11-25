/**
 * 认证相关 API 接口
 * 纯接口定义，通过 USE_MOCK 路由到 Mock 或 Real 实现
 */

import { USE_MOCK } from './provider.js'
import http from './http.js'

let _mock = null
async function mock() {
  if (!_mock) _mock = await import('./mock/auth.mock.js')
  return _mock
}

export async function login(username, password) {
  if (USE_MOCK) return (await mock()).login(username, password)
  return http.post('/auth/login', { username, password })
}

export async function register(username, password, nickname) {
  if (USE_MOCK) return (await mock()).register(username, password, nickname)
  return http.post('/auth/register', { username, password, nickname })
}

export async function logout(token) {
  if (USE_MOCK) return (await mock()).logout(token)
  return http.post('/auth/logout')
}

/**
 * 更新用户头像
 * 后端：POST /api/user/updateAvatar?avatarUrl=xxx
 */
export async function updateAvatar(avatarUrl) {
  if (USE_MOCK) return { code: 200, data: '头像更新成功' }
  return http.post('/user/updateAvatar', null, { params: { avatarUrl } })
}
