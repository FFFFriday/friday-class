import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { setUnauthorizedHandler } from './api/http'
import { useAuthStore } from './stores/auth'

// 全局样式。顺序不能反：tokens.css 定义变量，base.css 引用变量，
// 反过来的话 base.css 里所有 var() 都取不到值，页面会静默变成默认样式。
import './styles/tokens.css'
import './styles/base.css'

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
