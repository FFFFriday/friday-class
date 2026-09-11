import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'home', component: () => import('@/pages/HomePage.vue') },
  { path: '/login', name: 'login', component: () => import('@/pages/LoginPage.vue'), meta: { layout: false } },
  { path: '/upload', name: 'upload', component: () => import('@/pages/UploadPage.vue'), meta: { requiresAuth: true } },
  { path: '/courseware/:id', name: 'courseware-detail', component: () => import('@/pages/CoursewareDetailPage.vue') },
  { path: '/live/:sessionId', name: 'live', component: () => import('@/pages/LivePage.vue'), meta: { requiresAuth: true } },
  { path: '/profile', name: 'profile', component: () => import('@/pages/ProfilePage.vue'), meta: { requiresAuth: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach((to) => {
  if (to.meta.requiresAuth && !localStorage.getItem('token')) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
})

export default router
