/**
 * 全局测试 setup
 * 为 jsdom 环境注入 IndexedDB polyfill（Dexie 需要）
 */
import 'fake-indexeddb/auto'
import { config } from '@vue/test-utils'

const noopDirective = {
  mounted() {},
  updated() {}
}

config.global.directives = {
  motion: noopDirective,
  'motion-card-spring': noopDirective,
  'motion-empty-fade': noopDirective
}
