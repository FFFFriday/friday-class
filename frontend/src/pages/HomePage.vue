<script setup>
// 登录后的首页。本身不画界面，只按角色分发：
//   教师、管理员 → TeacherHome（慕课分节型：欢迎横幅 + 我的课件 + 全部课件）
//   学生         → StudentHome（课程中心型：浏览全部公开课件）
//
// 布局（顶栏）是同一套 AppLayout，只是菜单项按角色增删；
// 首页内容因为两种角色要的东西根本不同，所以拆成两个组件，而不是在一个文件里塞满 v-if。
import { useAuthStore } from '@/stores/auth'
import StudentHome from '@/pages/home/StudentHome.vue'
import TeacherHome from '@/pages/home/TeacherHome.vue'

const auth = useAuthStore()
</script>

<template>
  <!-- ready 之前角色未知。这时必须先等一下，否则教师会被当成学生渲染一帧
       （路由守卫已经 await 过 ensureUser，正常进不来这个分支，属于兜底）。 -->
  <div v-if="!auth.ready" class="loading">加载中…</div>

  <!-- 令牌还在却取不到账号信息（网络/后端故障）。这时角色是未知的，
       绝不能让它落到下面的 StudentHome——教师会看到学生界面且上传入口消失。 -->
  <div v-else-if="auth.loadError" class="loading error">
    取不到账号信息，请确认后端服务已启动后刷新页面重试
  </div>

  <!-- 管理员走教师首页：需求是「管理员是权限更大的教师」。
       后端也相应把 /session/mine、/session/taught 等接口放行了 ADMIN，
       否则这一页会拉到 403、「我的课堂」整块报错。 -->
  <TeacherHome v-else-if="auth.isStaff" />
  <StudentHome v-else />
</template>

<style scoped>
.loading {
  text-align: center;
  color: #999;
  padding: 80px 0;
  font-size: 14px;
}

.loading.error {
  color: #e74c3c;
}
</style>
