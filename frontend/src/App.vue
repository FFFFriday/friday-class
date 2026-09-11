<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'

const route = useRoute()
const showLayout = computed(() => route.meta.layout !== false)
</script>

<template>
  <AppLayout v-if="showLayout">
    <router-view />
  </AppLayout>
  <router-view v-else />
</template>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  background: #f7f7f8;
  color: #333;
}
</style>
