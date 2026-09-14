import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

// 除登录页外，全部要求登录——未登录的访客只看得到登录页，看不到任何其他界面。
// /courseware 曾经是公开的门户页（后端也仍是匿名放行的），但产品上改成登录后才可见。
const routes = [
  { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue'), meta: { requiresAuth: true } },
  { path: '/login', name: 'login', component: () => import('@/pages/LoginPage.vue'), meta: { layout: false } },
  {
    path: '/courseware',
    name: 'courseware-list',
    component: () => import('@/pages/CoursewareListPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/courseware/:id',
    name: 'courseware-detail',
    component: () => import('@/pages/CoursewareDetailPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/upload',
    name: 'upload',
    component: () => import('@/pages/UploadPage.vue'),
    // teacherOnly 只是前端体验（学生不该点进来看到上传表单）。
    // 真正的权限边界在后端：POST /courseware/upload 上有 @PreAuthorize("hasRole('TEACHER')")。
    meta: { requiresAuth: true, teacherOnly: true },
  },
  { path: '/live/:sessionId', name: 'live', component: () => import('@/pages/LivePage.vue'), meta: { requiresAuth: true } },
  {
    // 教师端直播控制台：开课后进来，负责翻页。
    // teacherOnly 只是前端体验；真正的边界在后端
    // （POST /api/session/*/page 在 SecurityConfig 与 @PreAuthorize 上都限了 TEACHER）。
    path: '/teach/:sessionId',
    name: 'teach',
    component: () => import('@/pages/TeacherLivePage.vue'),
    meta: { requiresAuth: true, teacherOnly: true },
  },
  { path: '/profile', name: 'profile', component: () => import('@/pages/ProfilePage.vue'), meta: { requiresAuth: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // 已登录还去登录页 → 直接回首页，避免出现「登录了还能看到登录页」的怪状态。
  if (to.name === 'login') {
    return auth.isLoggedIn ? { name: 'home' } : true
  }

  if (to.meta.requiresAuth && !auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // 刷新页面后内存里没有 user（只有 localStorage 的 token），角色未知。
  // 必须先补齐再放行，否则教师会被当成学生渲染一帧。
  if (to.meta.requiresAuth) {
    await auth.ensureUser()

    // 复检登录态：ensureUser 打到 /auth/me 期间令牌可能刚好失效，
    // 那一刻会话已被清掉。不复查的话，后面按角色做的重定向会基于
    // 已经过期的判断再跳一次，来回反弹。
    if (!auth.isLoggedIn) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }

    if (to.meta.teacherOnly && !auth.isTeacher) {
      return { name: 'home' }
    }
  }

  return true
})

export default router
