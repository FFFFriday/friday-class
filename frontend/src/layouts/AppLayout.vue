<script setup>
// 全局顶栏。教师端和学生端**共用这一套布局**，区别只在菜单项按角色增删。
//
// 改造前的三个问题：
//   1. 第一个菜单写着「门户」，业务上其实就是首页；
//   2. isLoggedIn 是 setup 里读一次 localStorage 的普通常量，非响应式；
//   3. 菜单写死，「上传课件」和「账户中心」不分角色全都显示。
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const keyword = ref('')
const menuOpen = ref(false)
const accountBtn = ref(null)

// 关闭下拉并把焦点还给触发按钮：否则键盘用户按 Esc 后焦点会掉到页面末尾。
function closeMenu() {
  menuOpen.value = false
  accountBtn.value?.focus()
}

// 不能靠 router-link 自带的 active class 来判断「首页」是否高亮：
// to="/" 是所有路径的前缀，/courseware 也会让它蹭上 router-link-active。
function isActive(path) {
  if (path === '/') return route.name === 'home'
  return route.path === path || route.path.startsWith(`${path}/`)
}

function doSearch() {
  const q = keyword.value.trim()
  router.push({ name: 'courseware-list', query: q ? { keyword: q } : {} })
}

function logout() {
  menuOpen.value = false
  auth.logout()
  router.push({ name: 'login' })
}

// 切页面时把下拉收起来，否则跳转后菜单还悬浮在内容上
watch(() => route.fullPath, () => {
  menuOpen.value = false
})
</script>

<template>
  <div class="layout">
    <header class="topbar">
      <div class="topbar-inner">
        <router-link class="brand" to="/">周五课堂</router-link>

        <nav class="nav">
          <router-link class="nav-link" :class="{ active: isActive('/') }" to="/">首页</router-link>
          <router-link class="nav-link" :class="{ active: isActive('/courseware') }" to="/courseware">
            课程中心
          </router-link>
          <!-- 教师专属。学生看不到入口；就算手敲 /upload 也会被路由守卫和
               后端 @PreAuthorize 两道拦下。 -->
          <router-link v-if="auth.isTeacher" class="nav-link" :class="{ active: isActive('/upload') }" to="/upload">
            上传课件
          </router-link>
        </nav>

        <div class="search">
          <!-- placeholder 不算可访问名称（一输入就消失），所以显式给 aria-label -->
          <input
            v-model="keyword"
            aria-label="搜索课件"
            placeholder="搜索课件…"
            @keyup.enter="doSearch"
          />
        </div>

        <div class="account" @keydown.esc="closeMenu">
          <button
            ref="accountBtn"
            class="account-btn"
            type="button"
            aria-haspopup="menu"
            :aria-expanded="menuOpen"
            aria-controls="account-menu"
            @click="menuOpen = !menuOpen"
          >
            <span class="avatar">{{ auth.avatarText }}</span>
            <span class="who">{{ auth.displayName }}</span>
            <span class="caret">▾</span>
          </button>

          <!-- 点空白处关下拉。用一层透明遮罩，比手写 document 事件监听不容易漏解绑。 -->
          <div v-if="menuOpen" class="backdrop" @click="closeMenu" />
          <div v-if="menuOpen" id="account-menu" class="menu" role="menu">
            <div class="menu-head">
              <p class="menu-name">{{ auth.displayName }}</p>
              <p class="menu-role">{{ auth.roleText }}</p>
            </div>
            <router-link class="menu-item" role="menuitem" to="/profile">账户中心</router-link>
            <button class="menu-item danger" type="button" role="menuitem" @click="logout">
              退出登录
            </button>
          </div>
        </div>
      </div>
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
  position: sticky;
  top: 0;
  z-index: 50;
  background: #fff;
  border-bottom: 1px solid #eee;
}

/* 顶栏内容与页面内容同宽居中，纵向才对得齐 */
.topbar-inner {
  max-width: 1120px;
  margin: 0 auto;
  height: 62px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 28px;
}

.brand {
  font-size: 18px;
  font-weight: 700;
  color: #d97757;
  text-decoration: none;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.nav {
  display: flex;
  gap: 4px;
}

.nav-link {
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 14px;
  color: #555;
  text-decoration: none;
  transition: color 0.15s, background 0.15s;
}

.nav-link:hover {
  color: #d97757;
  background: #fdf6f2;
}

.nav-link.active {
  color: #d97757;
  font-weight: 600;
}

.search {
  margin-left: auto;
}

.search input {
  width: 200px;
  padding: 8px 14px;
  border: 1px solid #ddd;
  border-radius: 20px;
  font-size: 13px;
  background: #f7f7f8;
  transition: width 0.2s, border-color 0.15s, background 0.15s;
}

.search input:focus {
  outline: none;
  width: 240px;
  border-color: #d97757;
  background: #fff;
}

.account {
  position: relative;
  z-index: 100;
}

.account-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px 5px 5px;
  border: none;
  background: transparent;
  border-radius: 20px;
  cursor: pointer;
}

.account-btn:hover {
  background: #f5f5f5;
}

.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: linear-gradient(135deg, #d97757, #eeae91);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.who {
  font-size: 14px;
  color: #333;
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret {
  font-size: 10px;
  color: #999;
}

.backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
}

.menu {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: 100;
  width: 176px;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.1);
  overflow: hidden;
  padding: 6px;
}

.menu-head {
  padding: 10px 12px 12px;
  border-bottom: 1px solid #f0f0f0;
  margin-bottom: 6px;
}

.menu-name {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-role {
  margin-top: 3px;
  font-size: 12px;
  color: #d97757;
}

.menu-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 9px 12px;
  border: none;
  background: transparent;
  border-radius: 6px;
  font-size: 14px;
  color: #333;
  text-decoration: none;
  cursor: pointer;
}

.menu-item:hover {
  background: #f5f5f5;
}

.menu-item.danger {
  color: #e74c3c;
}

.menu-item.danger:hover {
  background: #fdeaea;
}

.content {
  flex: 1;
  padding: 30px 24px 70px;
}

/* 窄屏先牺牲搜索框，导航和账号是主功能 */
@media (max-width: 820px) {
  .search,
  .who,
  .caret {
    display: none;
  }
}
</style>
