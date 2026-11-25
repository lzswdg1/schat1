<template>
  <div class="register-page">
    <div class="auth-card" v-motion-card-spring>
      <h2 class="auth-title">注册</h2>
      <form class="auth-form" @submit.prevent="handleRegister">
        <div class="form-group">
          <input
            v-model="username"
            type="text"
            class="form-input"
            placeholder="请输入账号 ID"
            autocomplete="username"
          />
        </div>
        <div class="form-group">
          <input
            v-model="nickname"
            type="text"
            class="form-input"
            placeholder="请输入昵称"
            autocomplete="nickname"
          />
        </div>
        <div class="form-group">
          <input
            v-model="password"
            type="password"
            class="form-input"
            placeholder="请输入密码（至少8位）"
            autocomplete="new-password"
          />
        </div>
        <div class="form-group">
          <input
            v-model="confirmPassword"
            type="password"
            class="form-input"
            placeholder="请确认密码"
            autocomplete="new-password"
          />
        </div>
        <p v-if="errorMsg" class="error-msg">{{ errorMsg }}</p>
        <p v-if="successMsg" class="success-msg">{{ successMsg }}</p>
        <button type="submit" class="btn-primary" :disabled="loading">
          {{ loading ? '注册中...' : '注册' }}
        </button>
      </form>
      <p class="auth-link">
        已有账号？<router-link to="/login">去登录</router-link>
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth.js'

const router = useRouter()
const authStore = useAuthStore()

const username = ref('')
const nickname = ref('')
const password = ref('')
const confirmPassword = ref('')
const errorMsg = ref('')
const successMsg = ref('')
const loading = ref(false)

async function handleRegister() {
  errorMsg.value = ''
  successMsg.value = ''

  if (!username.value.trim()) {
    errorMsg.value = '请输入账号 ID'
    return
  }

  if (password.value.length < 8) {
    errorMsg.value = '密码长度不能少于8位'
    return
  }

  if (password.value !== confirmPassword.value) {
    errorMsg.value = '密码不匹配'
    return
  }

  loading.value = true
  try {
    const res = await authStore.register(username.value, password.value, nickname.value.trim() || undefined)
    if (res.code === 200) {
      router.push('/login')
    } else {
      errorMsg.value = res.message
    }
  } finally {
    loading.value = false
  }
}
</script>

<style lang="scss" scoped>
.register-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: linear-gradient(135deg, #e8f5e9 0%, #f5f5f5 50%, #e3f2fd 100%);
}

.auth-card {
  width: 360px;
  padding: var(--spacing-xxl);
  background-color: var(--bg-white);
  border-radius: 16px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.08), 0 1px 3px rgba(0, 0, 0, 0.04);
}

.auth-title {
  margin: 0 0 var(--spacing-xl);
  font-size: 28px;
  font-weight: 700;
  color: var(--text-primary);
  text-align: center;
  letter-spacing: -0.5px;
}

.auth-form {
  display: flex;
  flex-direction: column;
  gap: var(--spacing-lg);
}

.form-group {
  display: flex;
  flex-direction: column;
}

.form-input {
  width: 100%;
  padding: 14px var(--spacing-lg);
  font-size: var(--font-size-md);
  color: var(--text-primary);
  background-color: #f8f8f8;
  border: 1.5px solid transparent;
  border-radius: 12px;
  outline: none;
  transition: border-color 300ms cubic-bezier(0.22, 1, 0.36, 1),
              background-color 300ms ease,
              box-shadow 300ms ease;
  box-sizing: border-box;

  &::placeholder {
    color: var(--text-placeholder);
  }

  &:focus {
    border-color: var(--color-primary);
    background-color: var(--bg-white);
    box-shadow: 0 0 0 3px rgba(7, 193, 96, 0.12);
  }
}

.error-msg {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--text-danger);
  text-align: center;
}

.success-msg {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-primary);
  text-align: center;
}

.btn-primary {
  width: 100%;
  padding: 14px;
  font-size: var(--font-size-lg);
  font-weight: 600;
  color: var(--text-inverse);
  background: linear-gradient(135deg, #07c160 0%, #06ad56 100%);
  border: none;
  border-radius: 12px;
  cursor: pointer;
  transition: transform 200ms cubic-bezier(0.22, 1, 0.36, 1),
              box-shadow 200ms ease,
              opacity 200ms ease;

  &:hover:not(:disabled) {
    transform: translateY(-1px);
    box-shadow: 0 4px 16px rgba(7, 193, 96, 0.3);
  }

  &:active:not(:disabled) {
    transform: translateY(0) scale(0.98);
    box-shadow: none;
    transition-duration: 80ms;
  }

  &:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.auth-link {
  margin: var(--spacing-lg) 0 0;
  font-size: var(--font-size-sm);
  color: var(--text-secondary);
  text-align: center;

  a {
    color: var(--text-link);
    text-decoration: none;

    &:hover {
      text-decoration: underline;
    }
  }
}
</style>
