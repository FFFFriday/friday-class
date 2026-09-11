<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'

const route = useRoute()
const detail = ref(null)
const pages = ref([])
const current = ref(0)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const id = route.params.id
    detail.value = await http.get(`/courseware/${id}`)
    const data = await http.get(`/courseware/${id}/pages`)
    pages.value = data.list
    current.value = 0
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function selectPage(i) {
  current.value = i
}

watch(() => route.params.id, load, { immediate: true })
</script>

<template>
  <div class="detail">
    <h1 class="heading">{{ detail?.name || '课件详情' }}</h1>
    <p v-if="detail" class="meta">共 {{ detail.pageCount }} 页 · 状态 {{ detail.status }}</p>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty error-text">{{ error }}</div>

    <div v-else class="body">
      <aside class="page-list">
        <button
          v-for="(p, i) in pages"
          :key="p.id"
          class="page-item"
          :class="{ active: i === current }"
          @click="selectPage(i)"
        >
          第 {{ p.pageNo }} 页
        </button>
      </aside>

      <section class="preview">
        <template v-if="pages.length">
          <h2 class="page-no">第 {{ pages[current].pageNo }} 页</h2>
          <p class="text">{{ pages[current].textContent }}</p>
          <p class="slide-link">幻灯片地址：{{ pages[current].slideUrl }}</p>
        </template>
        <p v-else class="empty">暂无页面</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.detail {
  max-width: 960px;
  margin: 0 auto;
}
.heading {
  font-size: 22px;
  margin-bottom: 8px;
}
.meta {
  color: #999;
  font-size: 13px;
  margin-bottom: 16px;
}
.body {
  display: flex;
  gap: 16px;
}
.page-list {
  width: 160px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 70vh;
  overflow-y: auto;
}
.page-item {
  padding: 10px;
  border: 1px solid #eee;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
  text-align: left;
  font-size: 14px;
  color: #666;
}
.page-item.active {
  border-color: #d97757;
  color: #d97757;
  font-weight: 600;
}
.preview {
  flex: 1;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 24px;
  min-height: 400px;
}
.page-no {
  font-size: 16px;
  margin-bottom: 12px;
}
.text {
  color: #444;
  line-height: 1.8;
  font-size: 15px;
}
.slide-link {
  margin-top: 20px;
  color: #999;
  font-size: 12px;
}
.empty {
  color: #999;
  text-align: center;
  padding: 40px 0;
}
.error-text {
  color: #e74c3c;
}
</style>
