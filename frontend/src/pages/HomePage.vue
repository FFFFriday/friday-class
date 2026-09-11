<script setup>
import { ref, onMounted } from 'vue'
import http from '@/api/http'

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const keyword = ref('')
const status = ref('')
const loading = ref(false)
const error = ref('')

const statusOptions = [
  { label: '全部状态', value: '' },
  { label: '已上传', value: 'UPLOADED' },
  { label: '转换中', value: 'CONVERTING' },
  { label: '已转换', value: 'CONVERTED' },
  { label: '解析中', value: 'PARSING' },
  { label: '已解析', value: 'PARSED' },
  { label: '失败', value: 'FAILED' },
]

const statusText = {
  UPLOADED: '已上传',
  CONVERTING: '转换中',
  CONVERTED: '已转换',
  PARSING: '解析中',
  PARSED: '已解析',
  FAILED: '失败',
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/courseware', {
      params: { page: page.value, size: size.value, keyword: keyword.value, status: status.value },
    })
    list.value = data.list
    total.value = data.total
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function prevPage() {
  if (page.value <= 1) return
  page.value--
  load()
}

function nextPage() {
  if (page.value * size.value >= total.value) return
  page.value++
  load()
}

onMounted(load)
</script>

<template>
  <div class="home">
    <h1 class="heading">课件库</h1>

    <div class="toolbar">
      <input v-model="keyword" placeholder="搜索课件名称…" @keyup.enter="search" />
      <select v-model="status" @change="search">
        <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <button class="btn" @click="search">搜索</button>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty error-text">{{ error }}</div>

    <template v-else>
      <div v-if="list.length === 0" class="empty">暂无课件</div>

      <ul v-else class="cw-list">
        <li v-for="c in list" :key="c.id" class="cw-item">
          <router-link class="cw-link" :to="`/courseware/${c.id}`">
            <div class="cw-main">
              <span class="cw-name">{{ c.name }}</span>
              <span class="cw-meta">{{ c.pageCount }} 页 · {{ c.uploaderName }} · {{ c.uploadedAt }}</span>
            </div>
            <span class="cw-status" :class="(c.status || '').toLowerCase()">{{ statusText[c.status] || c.status }}</span>
          </router-link>
        </li>
      </ul>

      <div class="pager">
        <button :disabled="page <= 1" @click="prevPage">上一页</button>
        <span>第 {{ page }} 页 · 共 {{ total }} 条</span>
        <button :disabled="page * size >= total" @click="nextPage">下一页</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.home {
  max-width: 880px;
  margin: 0 auto;
}
.heading {
  font-size: 22px;
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}
.toolbar input {
  flex: 1;
  padding: 9px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}
.toolbar select {
  padding: 9px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}
.toolbar input:focus,
.toolbar select:focus {
  outline: none;
  border-color: #d97757;
}
.btn {
  padding: 9px 18px;
  border: none;
  border-radius: 6px;
  background: #d97757;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}
.empty {
  text-align: center;
  color: #999;
  padding: 60px 0;
}
.error-text {
  color: #e74c3c;
}
.cw-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.cw-item {
  background: #fff;
  border: 1px solid #eee;
  border-radius: 8px;
  transition: box-shadow 0.15s;
}
.cw-item:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}
.cw-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  text-decoration: none;
  color: inherit;
}
.cw-main {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.cw-name {
  font-size: 15px;
  font-weight: 500;
  color: #333;
}
.cw-meta {
  font-size: 13px;
  color: #999;
}
.cw-status {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 20px;
  white-space: nowrap;
}
.cw-status.parsed {
  background: #e8f7ee;
  color: #27ae60;
}
.cw-status.failed {
  background: #fdeaea;
  color: #e74c3c;
}
.cw-status.uploaded,
.cw-status.converting,
.cw-status.converted,
.cw-status.parsing {
  background: #fff3e6;
  color: #e67e22;
}
.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  font-size: 14px;
  color: #666;
}
.pager button {
  padding: 7px 14px;
  border: 1px solid #ddd;
  background: #fff;
  border-radius: 6px;
  cursor: pointer;
}
.pager button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
