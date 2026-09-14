<script setup>
// 学生首页：课程中心型。
//
// ⚠️ 为什么不是「我的课程」：数据库里没有选课表，后端也没有任何
// 「学生选了哪些课」的接口（只有一个公开的 GET /api/courseware 课件列表）。
// 硬做「我的课程」只能编假数据，所以这一轮诚实地做成「浏览全部课件」。
// 真要做选课，需要后端加表 + 加接口，属于另一个任务。
import { onMounted, ref } from 'vue'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import CoursewareCard from '@/components/CoursewareCard.vue'

const auth = useAuthStore()

const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/courseware', {
      params: { page: page.value, size: size.value, keyword: keyword.value },
    })
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

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="head">
      <h1 class="title">你好，{{ auth.displayName }}</h1>
      <p class="sub">找一门课的课件</p>
      <!-- 用 <form> 而不是 @keyup.enter：原生回车提交、自动填充与无障碍树都免费拿到 -->
      <form class="search-form" @submit.prevent="search">
        <input
          v-model="keyword"
          class="search"
          type="search"
          aria-label="搜索课件"
          placeholder="搜索课件名称，回车开始…"
        />
      </form>
    </section>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="error" class="hint error-text">{{ error }}</div>

    <template v-else>
      <header class="section-head">
        <h2 class="section-title">全部课件</h2>
        <span class="count">共 {{ total }} 个</span>
      </header>

      <div v-if="list.length === 0" class="hint">没有找到符合条件的课件</div>

      <div v-else class="grid">
        <CoursewareCard v-for="c in list" :key="c.id" :courseware="c" />
      </div>

      <div v-if="total > size" class="pager">
        <button :disabled="page <= 1" @click="prevPage">上一页</button>
        <span>第 {{ page }} 页</span>
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

.head {
  padding: 30px 0 26px;
  border-bottom: 1px solid #eee;
  margin-bottom: 26px;
}

.title {
  font-size: 26px;
  color: #333;
}

.sub {
  margin-top: 8px;
  font-size: 14px;
  color: #999;
}

.search-form {
  margin-top: 20px;
  max-width: 460px;
}

.search {
  width: 100%;
  padding: 12px 16px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  background: #fff;
}

.search:focus {
  outline: none;
  border-color: #d97757;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 16px;
}

.section-title {
  font-size: 18px;
  color: #333;
}

.count {
  font-size: 13px;
  color: #999;
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
