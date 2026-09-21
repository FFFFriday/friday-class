<script setup>
// 教师端班级管理（问题点 6）。
//
// 页面本体在共享组件 ClassGroupManager.vue 里 —— 教师端与管理端要做的事
// 几乎完全一样，差别只有接口前缀与几列。写两份的代价是「改一处忘另一处」，
// 而漏掉的那次往往正是安全相关的那次。
//
// 这里的 admin=false 只是体验层：真正的边界在后端 ——
// /api/class-groups/** 在 SecurityConfig 上限 TEACHER/ADMIN，
// 而「这个班是不是你建的」由 ClassGroupService.requireOwnedGroup 兜底。
import ClassGroupManager from '@/components/features/ClassGroupManager.vue'
</script>

<template>
  <ClassGroupManager />
</template>
