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
    // staffOnly 只是前端体验（学生不该点进来看到上传表单）。
    // 真正的权限边界在后端：POST /courseware/upload 上有 @PreAuthorize("hasRole('TEACHER')")。
    meta: { requiresAuth: true, staffOnly: true },
  },
  { path: '/live/:sessionId', name: 'live', component: () => import('@/pages/LivePage.vue'), meta: { requiresAuth: true } },
  {
    // 教师端直播控制台：开课后进来，负责翻页。
    // staffOnly 只是前端体验；真正的边界在后端
    // （POST /api/session/*/page 在 SecurityConfig 与 @PreAuthorize 上都限了 TEACHER）。
    path: '/teach/:sessionId',
    name: 'teach',
    component: () => import('@/pages/TeacherLivePage.vue'),
    meta: { requiresAuth: true, staffOnly: true },
  },
  {
    // AI 助手：学生的长期会话（可跨课、可课后使用）。
    // /ai 是「还没选会话」的那一屏，/ai/:conversationId 是具体会话。
    // 两个路由指向同一个组件：拆成两个页面会让会话侧栏写两遍，
    // 而且从列表进会话会整页重挂载、丢滚动位置。
    // 不加角色限制——教师也可以有自己的 AI 会话（备课答疑）。
    path: '/ai',
    name: 'ai',
    component: () => import('@/pages/AiChatPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    path: '/ai/:conversationId',
    name: 'ai-chat',
    component: () => import('@/pages/AiChatPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    // 纯净演示页：老师**共享出去的就是这个标签页**。
    // layout: false 是关键——不带任何导航栏与外壳，学生看到的只有幻灯片本身。
    // staffOnly 只是前端体验；真正的边界在后端
    // （POST /api/session/*/page 在 SecurityConfig 与 @PreAuthorize 上都限了 TEACHER）。
    path: '/present/:sessionId',
    name: 'present',
    component: () => import('@/pages/TeachPresentPage.vue'),
    meta: { requiresAuth: true, staffOnly: true, layout: false },
  },
  {
    // AI 智能体（问题点 2 的第 5 条）：让模型自己去查数据、并往服务器上写文件。
    //
    // 与 /ai（纯聊天）是**两个页面**：那边只做一问一答，是只读的；
    // 这边会让模型产生副作用（写文件），所以限教师与管理员。
    //
    // agentOnly 只是前端体验层。**真正的墙在后端**：
    // SecurityConfig 里 /api/agent/** → hasAnyRole('TEACHER','ADMIN')，
    // 已实测学生 token 打任何 /api/agent/** 都是 403。
    path: '/agent',
    name: 'agent',
    component: () => import('@/pages/AgentPage.vue'),
    meta: { requiresAuth: true, agentOnly: true },
  },
  {
    // 教师端班级管理（问题点 6）：建班、加人、看这个班开过哪些课。
    // staffOnly 只是前端体验；后端在 SecurityConfig 上把
    // /api/class-groups/** 限成 TEACHER 或 ADMIN，且 Service 里还会校验
    // 「这个班是不是你建的」（教师只能碰自己的班）。
    path: '/teacher/classes',
    name: 'teacher-classes',
    component: () => import('@/pages/TeacherClassesPage.vue'),
    meta: { requiresAuth: true, staffOnly: true },
  },
  {
    // 课堂记录列表（修改文档3）：教师自己上过的**全部**课堂，含已结束的。
    // 教师首页的「课堂记录」栏只放最近 4 条，右上角「全部记录」跳到这。
    // 数据源是 /api/session/taught —— 它本来就返回含已结束的全部
    // （/session/mine 只返回未结束的，用它这个页面会永远是空的）。
    // staffOnly 只是前端体验；真正的边界在后端那条接口的 @PreAuthorize。
    path: '/teacher/records',
    name: 'teacher-records',
    component: () => import('@/pages/TeacherRecordsPage.vue'),
    meta: { requiresAuth: true, staffOnly: true },
  },
  {
    // 课堂记录：发言时间线 + 分学生 AI 问答 + 课后总结。
    // 权限是「本课堂的授课教师、任意管理员，或**这节课名单内的学生**」。
    // 学生能看到讨论区与参与名单，但 record/qa 只返回他自己的提问。
    // 真正的判定在后端（SessionRecordService.requireAccess /
    // SessionAccessService），前端不做角色守卫是有意的：
    // 管理员与名单内学生都要能进，而 staffOnly 会把他们都挡在门外。
    path: '/record/:sessionId',
    name: 'session-record',
    component: () => import('@/pages/SessionRecordPage.vue'),
    meta: { requiresAuth: true },
  },
  {
    // 管理端。用独立布局（左侧导航 + 内容区），不套 AppLayout 的顶栏。
    //
    // adminOnly 只是前端体验——学生根本不该看到管理入口。
    // **真正的墙在后端**：SecurityConfig 里 /api/admin/** → hasRole('ADMIN')，
    // 已实测学生与教师打任何管理端接口都是 403。
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAuth: true, adminOnly: true },
    children: [
      { path: '', name: 'admin-dashboard', component: () => import('@/pages/admin/AdminDashboard.vue') },
      { path: 'users', name: 'admin-users', component: () => import('@/pages/admin/AdminUsersPage.vue') },
      { path: 'classes', name: 'admin-classes', component: () => import('@/pages/admin/AdminClassesPage.vue') },
      { path: 'sessions', name: 'admin-sessions', component: () => import('@/pages/admin/AdminSessionsPage.vue') },
      { path: 'coursewares', name: 'admin-coursewares', component: () => import('@/pages/admin/AdminCoursewarePage.vue') },
      { path: 'audit', name: 'admin-audit', component: () => import('@/pages/admin/AdminAuditPage.vue') },
      // 这里**曾经**有一个 admin-agent 子路由（管理员在管理端外壳里用 AI 助手）。
      // 按需求删掉了：管理端只放「管别人」的功能，AI 助手是自己用的工具，
      // 走前台顶栏的 /agent 即可 —— 管理员本来就进得去（agentOnly 放行 TEACHER 与 ADMIN）。
    ],
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

    // 「教师专属」现在的含义是「教师或管理员」：需求明确管理员是权限更大的教师。
    // 元信息键名一并换了，免得下一个读代码的人被旧名字误导成「管理员进不来」——
    // 这个坑（角色判定把管理员挡在门外）项目里已经踩过两次。
    if (to.meta.staffOnly && !auth.isTeacher && !auth.isAdmin) {
      return { name: 'home' }
    }

    // 管理端路由守卫。注意这里**单独判 isAdmin**，不能用 staffOnly 那一套：
    // 管理员的角色是 ADMIN，isTeacher 为 false，套用上面那条会把他自己挡在门外。
    if (to.meta.adminOnly && !auth.isAdmin) {
      return { name: 'home' }
    }

    // AI 智能体：教师与管理员都能进，学生不能。
    // 同样不能复用上面两条 —— staffOnly 会把管理员挡住、adminOnly 会把教师挡住，
    // 这个坑与 admin 那一条是同一个。
    if (to.meta.agentOnly && !auth.isTeacher && !auth.isAdmin) {
      return { name: 'home' }
    }
  }

  return true
})

export default router
