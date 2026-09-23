<script setup>
// 管理端独立布局：左侧导航 + 右侧内容。
//
// 为什么不复用 AppLayout 的顶栏：管理端的导航是**六块并列的功能区**，
// 塞进顶栏会挤掉搜索框和账号菜单；而且管理端的操作是破坏性的
// （删账号、删课件、强制下课），视觉上与前台的「课堂」区隔开更安全——
// 避免管理员误以为自己在操作自己的账号。
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// 「班级管理」排在「账号管理」之后：两者都是「人和组织」，挨着更顺；
// 课堂管理是「正在发生的事」，排在它们之后。
const NAV = [
  { name: 'admin-dashboard', label: '概览', path: '/admin' },
  { name: 'admin-users', label: '账号管理', path: '/admin/users' },
  { name: 'admin-classes', label: '班级管理', path: '/admin/classes' },
  { name: 'admin-sessions', label: '课堂管理', path: '/admin/sessions' },
  { name: 'admin-coursewares', label: '课件与存储', path: '/admin/coursewares' },
  // AI 助手排在管理类功能之后：它不是「管别人」，而是管理员自己用的工具
  // （与教师在 /agent 用的是同一个页面，只是留在管理端布局里，见 router/index.js）。
  { name: 'admin-agent', label: 'AI 助手', path: '/admin/agent' },
  { name: 'admin-audit', label: '审计日志', path: '/admin/audit' },
]

const activeName = computed(() => route.name)

function logout() {
  auth.logout()
  router.push({ name: 'login' })
}
</script>

<template>
  <div class="admin">
    <aside class="admin__side">
      <div class="admin__brand">
        <span class="admin__logo">周五课堂</span>
        <span class="admin__badge">管理端</span>
      </div>

      <nav class="admin__nav">
        <router-link
          v-for="item in NAV"
          :key="item.name"
          class="admin__link"
          :class="{ 'admin__link--on': activeName === item.name }"
          :to="item.path"
        >
          {{ item.label }}
        </router-link>
      </nav>

      <div class="admin__foot">
        <p class="admin__who">
          <span class="admin__avatar">{{ auth.avatarText }}</span>
          <span class="admin__name">{{ auth.displayName }}</span>
        </p>
        <router-link class="admin__back" to="/">← 返回前台</router-link>
        <button class="admin__back" type="button" @click="logout">退出登录</button>
      </div>
    </aside>

    <main class="admin__main">
      <router-view />
    </main>
  </div>
</template>

<style scoped>
.admin {
  display: flex;
  min-height: 100vh;
  background: var(--fc-bg);
}

.admin__side {
  position: sticky;
  top: 0;
  height: 100vh;
  width: var(--fc-sidebar-width);
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #2b2724; /* 深色侧栏：与前台一眼区分开 */
  color: rgba(255, 255, 255, 0.85);
}

.admin__brand {
  display: flex;
  align-items: baseline;
  gap: var(--fc-space-2);
  padding: var(--fc-space-5) var(--fc-space-4);
}

.admin__logo {
  font-size: var(--fc-font-md);
  font-weight: var(--fc-weight-bold);
  color: var(--fc-text-invert);
  letter-spacing: 0.5px;
}

.admin__badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: var(--fc-radius-sm);
  background: var(--fc-primary);
  color: var(--fc-text-invert);
}

.admin__nav {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 0 var(--fc-space-2);
}

.admin__link {
  padding: var(--fc-space-2) var(--fc-space-3);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
  color: rgba(255, 255, 255, 0.72);
  text-decoration: none;
  transition: background var(--fc-transition), color var(--fc-transition);
}

.admin__link:hover {
  background: rgba(255, 255, 255, 0.08);
  color: var(--fc-text-invert);
}

.admin__link--on {
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  font-weight: var(--fc-weight-medium);
}

.admin__foot {
  padding: var(--fc-space-4);
  border-top: 1px solid rgba(255, 255, 255, 0.1);
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
}

.admin__who {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  margin: 0 0 var(--fc-space-2);
  font-size: var(--fc-font-sm);
}

.admin__avatar {
  width: 26px;
  height: 26px;
  border-radius: var(--fc-radius-circle);
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  font-size: var(--fc-font-xs);
  display: flex;
  align-items: center;
  justify-content: center;
}

.admin__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.admin__back {
  text-align: left;
  padding: 0;
  font-size: var(--fc-font-xs);
  color: rgba(255, 255, 255, 0.55);
  text-decoration: none;
  background: none;
  border: none;
}
.admin__back:hover {
  color: var(--fc-text-invert);
}

.admin__main {
  flex: 1;
  min-width: 0;
  padding: var(--fc-space-6);
}
</style>
