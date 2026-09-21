<script setup>
// 管理端班级管理（问题点 8）：全部班级，可跨教师操作。
//
// 与教师端共用 ClassGroupManager.vue，只是传 admin=true ——
// 多一个「所属教师」列与按教师筛选，接口走 /api/admin/class-groups
// （自动被 SecurityConfig 的 hasRole('ADMIN') 保护），写操作记审计。
import ClassGroupManager from '@/components/features/ClassGroupManager.vue'
</script>

<template>
  <ClassGroupManager admin />
</template>
