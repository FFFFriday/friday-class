// src/stores/auth.js
// 登录态的唯一真源（single source of truth）。
//
// 为什么要有这个 store：改造前 AppLayout 直接写
//     const isLoggedIn = !!localStorage.getItem('token')
// 这是**非响应式**的——登录成功后不刷新整页，顶栏不会变。
// 而且登录接口返回的 user（含 role）被当场丢掉了，导致前端拿不到角色、
// 教师和学生看到完全一样的界面。
//
// 这里把 token 和 user 一起收进来，并且都是 ref，改一个地方全站响应。
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import http from '@/api/http'

const TOKEN_KEY = 'token'
const USER_KEY = 'user'

/** 读 localStorage 里的 user。解析失败（手改坏了）就当没有，不抛异常。 */
function readStoredUser() {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw)
  } catch {
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const user = ref(readStoredUser())

  // 老会话兜底：本次改造之前登录的用户，localStorage 里只有 token、没有 user。
  // 这时 isTeacher 会误判成 false，把教师当学生渲染。所以需要一个「补票」动作，
  // 在路由守卫里 await 它一次（见 router/index.js）。
  // 已经有 user 时算作立刻就绪。
  const ready = ref(!token.value || !!user.value)

  // 「令牌还在、却拿不到用户信息」= 网络或后端故障，**不是**会话失效。
  // 这种模糊状态不能当成「学生」处理——教师会看到学生首页、上传入口还会莫名消失。
  // 记为错误态，由页面显式提示重试。
  const loadError = ref(false)

  const isLoggedIn = computed(() => !!token.value)
  const role = computed(() => user.value?.role || '')
  const isTeacher = computed(() => role.value === 'TEACHER')
  /** 管理员。只有他能进 /admin/**；真正的边界在后端 SecurityConfig。 */
  const isAdmin = computed(() => role.value === 'ADMIN')

  /**
   * **教师或管理员** —— 需求口径是「管理员是权限更大的教师」，
   * 所以凡是「教师能做的事」（上课、翻页、上传、看自己的课），管理员都应该能做。
   *
   * <p>为什么要单独抽这个 getter：把这个判断写成光秃秃的 `auth.isTeacher`
   * 已经在三个地方各错过一次（HomePage、LiveSessions、CoursewareDetailPage），
   * 症状都是**管理员被当成学生**、功能静默消失。有一个语义明确的名字，
   * 下一个写「教师能做的事」的人就不容易漏掉管理员。
   *
   * <p>⚠ 反过来也要小心：**只给管理员**的东西（管理端入口、跨教师管理）
   * 仍然要用 isAdmin，不要图省事换成这个。
   */
  const isStaff = computed(() => isTeacher.value || isAdmin.value)
  const displayName = computed(() => user.value?.nickname || user.value?.username || '')
  const avatarText = computed(() => displayName.value.slice(0, 1) || '?')
  const roleText = computed(
    () => ({ TEACHER: '教师', ADMIN: '管理员' })[role.value] || '学生',
  )

  /** 写入/清空会话，localStorage 与内存状态永远一起变。 */
  function setSession(nextToken, nextUser) {
    token.value = nextToken || ''
    user.value = nextUser || null

    if (token.value) localStorage.setItem(TOKEN_KEY, token.value)
    else localStorage.removeItem(TOKEN_KEY)

    if (user.value) localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    else localStorage.removeItem(USER_KEY)

    ready.value = true
  }

  function logout() {
    setSession('', null)
  }

  /**
   * 确保 user 已就绪。幂等：有 user 就直接返回，不会重复打接口。
   * 401（令牌失效）由 http.js 的响应拦截器统一处理并跳登录页，这里不重复处理。
   */
  async function ensureUser() {
    if (!token.value || user.value) {
      ready.value = true
      return
    }
    loadError.value = false
    try {
      const me = await http.get('/auth/me')
      if (me) {
        user.value = me
        localStorage.setItem(USER_KEY, JSON.stringify(me))
      }
    } catch {
      // 令牌失效的情形：401 已由 http.js 触发 handler → logout()，token 被清空，
      // 这不叫错误，交给守卫把人送去登录页就行。
      // 令牌**还在**却依然失败，才是真的取不到账号信息 → 记错误，别硬渲染成学生。
      loadError.value = !!token.value
    } finally {
      ready.value = true
    }
  }

  return {
    token,
    user,
    ready,
    loadError,
    isLoggedIn,
    role,
    isTeacher,
    isAdmin,
    isStaff,
    displayName,
    avatarText,
    roleText,
    setSession,
    logout,
    ensureUser,
  }
})
