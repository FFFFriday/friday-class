import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { useAuthStore } from './stores/auth'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 令牌失效时的统一出口：由 auth store 清会话（内存态 + localStorage 一起清），
// 再走前端路由跳登录页。写在这里而不是 http.js 里，是因为它需要 pinia 与 router 都就绪；
// 放在 http.js 里会造成 store → http → store 的循环依赖。
setUnauthorizedHandler(() => {
  useAuthStore().logout()
  if (router.currentRoute.value.name !== 'login') {
    router.push({ name: 'login' })
  }
})

app.mount('#app')
