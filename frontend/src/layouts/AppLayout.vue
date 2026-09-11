<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

const isLoggedIn = !!localStorage.getItem('token')

function logout() {
  localStorage.removeItem('token')
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <header class="topbar">
      <router-link class="brand" to="/">周五课堂</router-link>
      <nav class="nav">
        <button @click="router.push('/')">门户</button>
        <button v-if="isLoggedIn" @click="router.push('/upload')">上传课件</button>
        <button v-if="isLoggedIn" @click="router.push('/profile')">账户中心</button>
        <button v-if="!isLoggedIn" @click="router.push('/login')">登录</button>
        <button v-else @click="logout">退出</button>
      </nav>
    </header>
    <main class="content">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #eee;
}
.brand {
  font-size: 18px;
  font-weight: 600;
  cursor: pointer;
  color: #d97757;
  text-decoration: none;
}
.nav {
  display: flex;
  gap: 8px;
}
.nav button {
  border: none;
  background: transparent;
  padding: 8px 12px;
  cursor: pointer;
  border-radius: 6px;
  color: #333;
  font-size: 14px;
}
.nav button:hover {
  background: #f5f5f5;
}
.content {
  flex: 1;
  padding: 24px;
}
</style>
