<script setup>
// 全局顶栏。教师端和学生端**共用这一套布局**，区别只在菜单项按角色增删。
//
// 改造前的三个问题：
//   1. 第一个菜单写着「门户」，业务上其实就是首页；
//   2. isLoggedIn 是 setup 里读一次 localStorage 的普通常量，非响应式；
//   3. 菜单写死，「上传课件」和「账户中心」不分角色全都显示。
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// 顶栏菜单。改成**数据驱动**（照 AdminLayout.vue:18 的 NAV 写法）：
// 以后改名、增删项、调可见角色，都只动这一个数组，模板不用碰。
//
// ⚠️「AI 问答」与「AI 助手」是两个不同的页面，别混：
//   AI 问答 /ai     —— 只读的一问一答，教师和学生都能用；
//   AI 助手 /agent  —— 会真去查数据、往服务器写文件的智能体，只给教师与管理员。
//                      学生看不到入口，手敲 /agent 会被路由守卫弹回首页，
//                      真正的墙在后端（/api/agent/** → hasAnyRole('TEACHER','ADMIN')）。
//
// 注意这里**没有**「上传课件」：按修改文档3 从顶栏删掉了。
// 但 /upload 这个路由和上传页都还在——首页大卡片的「上传课件」按钮要跳它。
const NAV = [
  { label: '首页', path: '/', roles: null },
  { label: '课件中心', path: '/courseware', roles: null },
  { label: 'AI 问答', path: '/ai', roles: null },
  { label: 'AI 助手', path: '/agent', roles: ['TEACHER', 'ADMIN'] },
  // 管理端入口只对管理员显示。学生/教师手敲 /admin 会被路由守卫弹回首页，
  // 真正的墙在后端（/api/admin/** → hasRole('ADMIN')）。
  { label: '管理端', path: '/admin', roles: ['ADMIN'] },
]

/** roles 为 null = 所有人可见；否则按 auth.role 过滤。 */
const visibleNav = computed(() =>
  NAV.filter((item) => !item.roles || item.roles.includes(auth.role)),
)

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
          <router-link
            v-for="item in visibleNav"
            :key="item.path"
            class="nav-link"
            :class="{ active: isActive(item.path) }"
            :to="item.path"
          >
            {{ item.label }}
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
              <p class="menu-name">你好，{{ auth.displayName }}</p>
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
  background: var(--fc-bg-panel);
  border-bottom: 1px solid var(--fc-border);
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
  color: var(--fc-primary);
  text-decoration: none;
  letter-spacing: 0.5px;
  white-space: nowrap;
}

.nav {
  display: flex;
  gap: 4px;
  /* 菜单项永远不参与收缩：宁可让搜索框先让位，也不能把导航挤变形 */
  flex-shrink: 0;
}

.nav-link {
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 14px;
  color: var(--fc-text-muted);
  text-decoration: none;
  /* 不折行。默认的 white-space:normal 在窄屏下会把「课件中心」断成
     「课件中 / 心」—— 菜单项本来就只有两三个字，断在哪一行都很难看。 */
  white-space: nowrap;
  transition: color 0.15s, background 0.15s;
}

.nav-link:hover {
  color: var(--fc-accent);
  background: var(--fc-primary-bg-weak);
}

.nav-link.active {
  color: var(--fc-accent);
  font-weight: 600;
}

.search {
  margin-left: auto;
}

.search input {
  width: 200px;
  padding: 8px 14px;
  border: 1px solid var(--fc-border-strong);
  border-radius: 20px;
  font-size: 13px;
  background: var(--fc-bg);
  transition: width 0.2s, border-color 0.15s, background 0.15s;
}

.search input:focus {
  outline: none;
  width: 240px;
  border-color: var(--fc-accent);
  background: var(--fc-bg-panel);
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
  background: var(--fc-bg-muted);
}

.avatar {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  font-size: 13px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
}

.who {
  font-size: 14px;
  color: var(--fc-text);
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.caret {
  font-size: 10px;
  color: var(--fc-text-faint);
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
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: 10px;
  box-shadow: var(--fc-shadow-lg);
  overflow: hidden;
  padding: 6px;
}

.menu-head {
  padding: 10px 12px 12px;
  border-bottom: 1px solid var(--fc-border);
  margin-bottom: 6px;
}

.menu-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--fc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.menu-role {
  margin-top: 3px;
  font-size: 12px;
  color: var(--fc-primary);
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
  color: var(--fc-text);
  text-decoration: none;
  cursor: pointer;
}

.menu-item:hover {
  background: var(--fc-bg-muted);
}

.menu-item.danger {
  color: var(--fc-danger);
}

.menu-item.danger:hover {
  background: var(--fc-danger-bg);
}

.content {
  flex: 1;
  padding: 30px 24px 70px;
}

/* 窄屏先牺牲搜索框，导航和账号是主功能。
   阈值从 820 提到 960：菜单项加上搜索框 (200px) 在 820~960 这一段
   会把导航挤到折行，让搜索框提前让位就没这个问题。 */
@media (max-width: 960px) {
  .search,
  .who,
  .caret {
    display: none;
  }
}
</style>
