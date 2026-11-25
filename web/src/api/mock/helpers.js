/**
 * Mock 工具函数
 * 提供模拟网络延迟和统一响应构造能力
 */

/**
 * 模拟网络延迟（200ms-500ms 随机）
 * @returns {Promise<void>}
 */
export function delay() {
  return new Promise(resolve =>
    setTimeout(resolve, 200 + Math.random() * 300)
  )
}

/**
 * 构造统一 API 响应
 * @param {any} data - 业务数据
 * @param {number} code - 状态码，默认 200
 * @param {string} message - 描述信息，默认 'ok'
 * @returns {{ code: number, message: string, data: any }}
 */
export function mockResponse(data, code = 200, message = 'ok') {
  return { code, message, data }
}
