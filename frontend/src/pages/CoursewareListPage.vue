<script setup>
// 课程中心。改造前这就是首页（HomePage.vue），标题还叫「课件库」。
// 现在它降级成顶栏「课程中心」指向的子栏目，/courseware。
//
// 顶栏的搜索框回车后跳到这里并带上 ?keyword=，所以这里要 watch 路由 query，
// 否则用户在顶栏连续搜两次、第二次不会再触发请求。
import { onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import CoursewareCard from '@/components/CoursewareCard.vue'

const route = useRoute()

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/courseware', {
      params: { page: page.value, size: size.value, keyword: keyword.value, status: status.value },
    })
    // 兜底：响应体缺字段时别让模板的 list.length / v-for 直接抛错白屏
    list.value = data.list || []
    total.value = data.total || 0
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

// 顶栏搜索跳过来时带 keyword，同步进输入框并重新查。
watch(
  () => route.query.keyword,
  (val) => {
    const next = val || ''
    if (next === keyword.value) return
    keyword.value = next
    search()
  },
)

onMounted(() => {
  keyword.value = route.query.keyword || ''
  load()
})
</script>

<template>
  <div class="page">
    <header class="page-head">
      <h1 class="heading">课程中心</h1>
      <p class="sub">浏览全部已上传的课件，点击卡片查看页面内容</p>
    </header>

    <div class="toolbar">
      <input
        v-model="keyword"
        class="search"
        aria-label="搜索课件名称"
        placeholder="搜索课件名称…"
        @keyup.enter="search"
      />
      <select v-model="status" class="select" aria-label="按解析状态筛选" @change="search">
        <option v-for="o in statusOptions" :key="o.value" :value="o.value">{{ o.label }}</option>
      </select>
      <button class="btn" type="button" @click="search">搜索</button>
    </div>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="error" class="hint error-text">{{ error }}</div>

    <template v-else>
      <div v-if="list.length === 0" class="hint">没有找到符合条件的课件</div>

      <div v-else class="grid">
        <CoursewareCard v-for="c in list" :key="c.id" :courseware="c" />
      </div>

      <div v-if="total > size" class="pager">
        <button :disabled="page <= 1" @click="prevPage">上一页</button>
        <span>第 {{ page }} 页 · 共 {{ total }} 个课件</span>
        <button :disabled="page * size >= total" @click="nextPage">下一页</button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page {
  max-width: 1120px;
  margin: 0 auto;
}

.page-head {
  margin-bottom: 18px;
}

.heading {
  font-size: 22px;
  color: #333;
}

.sub {
  margin-top: 6px;
  font-size: 13px;
  color: #999;
}

.toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 22px;
}

.search {
  flex: 1;
  padding: 10px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
}

.select {
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  color: #333;
}

.search:focus,
.select:focus {
  outline: none;
  border-color: #d97757;
}

.btn {
  padding: 10px 22px;
  border: none;
  border-radius: 8px;
  background: #d97757;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}

.btn:hover {
  background: #c9694a;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 18px;
}

.hint {
  text-align: center;
  color: #999;
  padding: 60px 0;
  font-size: 14px;
}

.error-text {
  color: #e74c3c;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 28px;
  font-size: 14px;
  color: #666;
}

.pager button {
  padding: 8px 16px;
  border: 1px solid #ddd;
  background: #fff;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
  color: #333;
}

.pager button:hover:not(:disabled) {
  border-color: #d97757;
  color: #d97757;
}

.pager button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
</style>
