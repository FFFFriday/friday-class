<script setup>
import { ref, onMounted } from 'vue'
import http from '@/api/http'

const user = ref(null)
const pwd = ref({ oldPassword: '', newPassword: '' })
const message = ref('')
const messageType = ref('success') // 'success' | 'error'
const loading = ref(false)
const error = ref('')
const submitting = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    user.value = await http.get('/auth/me')
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function changePwd() {
  if (!pwd.value.oldPassword || !pwd.value.newPassword) {
    message.value = '请填写完整'
    messageType.value = 'error'
    return
  }
  submitting.value = true
  try {
    await http.put('/auth/password', pwd.value)
    message.value = '密码已修改'
    messageType.value = 'success'
    pwd.value = { oldPassword: '', newPassword: '' }
  } catch (e) {
    message.value = e.message || '修改失败'
    messageType.value = 'error'
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="profile">
    <h1 class="heading">账户中心</h1>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty error-text">{{ error }}</div>

    <template v-else>
      <div class="card">
        <p v-if="user" class="who">
          <strong>{{ user.nickname || user.username }}</strong>
          <span class="role">{{ user.role === 'TEACHER' ? '教师' : '学生' }}</span>
        </p>
        <p v-if="user" class="meta">用户名：{{ user.username }}</p>
      </div>

      <div class="card">
        <h2 class="sub">修改密码</h2>
        <div class="form">
          <input v-model="pwd.oldPassword" type="password" placeholder="原密码" />
          <input v-model="pwd.newPassword" type="password" placeholder="新密码" />
          <button class="btn" :disabled="submitting" @click="changePwd">确认修改</button>
        </div>
        <p v-if="message" class="msg" :class="messageType">{{ message }}</p>
      </div>
    </template>
  </div>
</template>

<style scoped>
.profile {
  max-width: 560px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.heading {
  font-size: 22px;
}
.card {
  background: #fff;
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 20px 24px;
}
.who {
  font-size: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.role {
  font-size: 12px;
  color: #d97757;
  background: #fff3e6;
  padding: 2px 8px;
  border-radius: 20px;
}
.meta {
  color: #999;
  font-size: 13px;
  margin-top: 6px;
}
.sub {
  font-size: 15px;
  margin-bottom: 12px;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.form input {
  padding: 9px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}
.form input:focus {
  outline: none;
  border-color: #d97757;
}
.btn {
  padding: 9px 16px;
  border: none;
  border-radius: 6px;
  background: #d97757;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
  align-self: flex-start;
}
.btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.msg {
  margin-top: 10px;
  font-size: 13px;
}
.msg.success {
  color: #27ae60;
}
.msg.error {
  color: #e74c3c;
}
.empty {
  color: #999;
  text-align: center;
  padding: 40px 0;
}
.error-text {
  color: #e74c3c;
}
</style>
