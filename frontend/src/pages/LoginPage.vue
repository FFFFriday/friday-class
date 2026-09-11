<script setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import http from '@/api/http'

const router = useRouter()
const route = useRoute()
const mode = ref('login') // 'login' | 'register'
const form = ref({ username: '', password: '', nickname: '' })
const loading = ref(false)
const error = ref('')

function switchMode(m) {
  mode.value = m
  error.value = ''
}

async function submit() {
  error.value = ''
  if (!form.value.username || !form.value.password) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  try {
    let data
    if (mode.value === 'login') {
      data = await http.post('/auth/login', { username: form.value.username, password: form.value.password })
    } else {
      data = await http.post('/auth/register', form.value)
    }
    localStorage.setItem('token', data.token)
    router.push(route.query.redirect || '/')
  } catch (e) {
    error.value = e.message || '操作失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (localStorage.getItem('token')) router.push('/')
})
</script>

<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1 class="title">周五课堂</h1>
      <p class="subtitle">智能教学互动平台</p>

      <div class="tabs">
        <button :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
      </div>

      <form @submit.prevent="submit">
        <input v-model="form.username" placeholder="用户名" />
        <input v-model="form.password" type="password" placeholder="密码" />
        <template v-if="mode === 'register'">
          <input v-model="form.nickname" placeholder="昵称（选填）" />
          <p class="role-hint">注册后为学生账号；教师账号由管理员统一开通</p>
        </template>

        <p v-if="error" class="error">{{ error }}</p>

        <button class="submit" type="submit" :disabled="loading">
          {{ loading ? '提交中…' : mode === 'login' ? '登录' : '注册' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #fdf6f2, #f7f7f8);
}
.login-card {
  width: 360px;
  background: #fff;
  border-radius: 12px;
  padding: 36px 32px;
  box-shadow: 0 8px 30px rgba(0, 0, 0, 0.08);
}
.title {
  text-align: center;
  color: #d97757;
  font-size: 26px;
}
.subtitle {
  text-align: center;
  color: #999;
  font-size: 13px;
  margin: 6px 0 20px;
}
.tabs {
  display: flex;
  margin-bottom: 20px;
  border-bottom: 1px solid #eee;
}
.tabs button {
  flex: 1;
  border: none;
  background: transparent;
  padding: 10px;
  cursor: pointer;
  font-size: 15px;
  color: #666;
  border-bottom: 2px solid transparent;
}
.tabs button.active {
  color: #d97757;
  border-bottom-color: #d97757;
  font-weight: 600;
}
form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
input,
select {
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}
input:focus,
select:focus {
  outline: none;
  border-color: #d97757;
}
.error {
  color: #e74c3c;
  font-size: 13px;
}
.role-hint {
  color: #999;
  font-size: 12px;
  line-height: 1.5;
}
.submit {
  margin-top: 4px;
  padding: 11px;
  border: none;
  border-radius: 6px;
  background: #d97757;
  color: #fff;
  font-size: 15px;
  cursor: pointer;
}
.submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
