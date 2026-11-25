import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import { MotionPlugin } from '@vueuse/motion'
import App from './App.vue'
import router from './router'
import './styles/index.scss'

const app = createApp(App)

const pinia = createPinia()
pinia.use(piniaPluginPersistedstate)
app.use(pinia)
app.use(router)

// 初始化 WebSocket（安装 Mock 桥接等）并恢复连接
import { initWebSocket } from './api/websocket.js'
import { useAuthStore } from './stores/auth.js'
initWebSocket().then(() => {
  const authStore = useAuthStore()
  authStore.restoreConnection()
})

// 注册 @vueuse/motion 动画插件，配置自定义 spring 预设
app.use(MotionPlugin, {
  directives: {
    // 消息气泡 — 从底部弹入，spring 物理
    'msg-in': {
      initial: { opacity: 0, y: 20, scale: 0.94 },
      enter: {
        opacity: 1, y: 0, scale: 1,
        transition: { type: 'spring', stiffness: 260, damping: 20, mass: 0.8 }
      }
    },
    // 自己的消息 — 从右侧滑入
    'msg-self': {
      initial: { opacity: 0, x: 24, scale: 0.94 },
      enter: {
        opacity: 1, x: 0, scale: 1,
        transition: { type: 'spring', stiffness: 280, damping: 22 }
      }
    },
    // 他人的消息 — 从左侧滑入
    'msg-other': {
      initial: { opacity: 0, x: -24, scale: 0.94 },
      enter: {
        opacity: 1, x: 0, scale: 1,
        transition: { type: 'spring', stiffness: 280, damping: 22 }
      }
    },
    // 卡片弹入 — 登录/注册卡片
    'card-spring': {
      initial: { opacity: 0, y: 30, scale: 0.95 },
      enter: {
        opacity: 1, y: 0, scale: 1,
        transition: { type: 'spring', stiffness: 200, damping: 18, mass: 0.9 }
      }
    },
    // 侧边栏项目 — 交错淡入
    'list-item': {
      initial: { opacity: 0, x: -12 },
      enter: {
        opacity: 1, x: 0,
        transition: { type: 'spring', stiffness: 300, damping: 24 }
      }
    },
    // 工具栏弹出
    'toolbar-pop': {
      initial: { opacity: 0, y: 8, scale: 0.88 },
      enter: {
        opacity: 1, y: 0, scale: 1,
        transition: { type: 'spring', stiffness: 400, damping: 20 }
      }
    },
    // 空状态淡入
    'empty-fade': {
      initial: { opacity: 0, y: 16, scale: 0.97 },
      enter: {
        opacity: 1, y: 0, scale: 1,
        transition: { type: 'spring', stiffness: 180, damping: 20, delay: 100 }
      }
    }
  }
})

app.mount('#app')
