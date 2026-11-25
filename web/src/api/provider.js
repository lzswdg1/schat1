/**
 * API Provider 切换
 * 默认真实后端；测试环境自动启用 Mock，避免单元测试依赖本地服务。
 * 本地需要强制 Mock 时可设置 VITE_USE_MOCK=true。
 */

export const USE_MOCK = import.meta.env.MODE === 'test' || import.meta.env.VITE_USE_MOCK === 'true'
