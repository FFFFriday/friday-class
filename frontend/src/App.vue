<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from '@/layouts/AppLayout.vue'
import { FcConfirmHost, FcToast } from '@/components/base'

const route = useRoute()
const showLayout = computed(() => route.meta.layout !== false)
</script>

<template>
  <AppLayout v-if="showLayout">
    <router-view />
  </AppLayout>
  <router-view v-else />

  <!--
    全局唯一的轻提示出口。放在布局之外、最外层：
    这样路由切换时它不会被卸载，跨页面报的提示才能留住。
    它自己也 Teleport 到 body，不受任何父级样式影响。

    原先这里的全局 reset 已移入 styles/base.css —— 取值完全一致
    （归零、border-box、同一套字体与底色），所以外观零变化。
  -->
  <FcToast />

  <!--
    全局唯一的确认/输入弹窗出口。和 FcToast 同样放在最外层：
    它替代了原先散落各页的 window.confirm / window.prompt，
    必须跨路由常驻，否则路由一切换，还挂着的那个 promise 就永远悬着了。
  -->
  <FcConfirmHost />
</template>
