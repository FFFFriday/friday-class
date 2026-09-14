<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const detail = ref(null)
const pages = ref([])
const current = ref(0)
const loading = ref(false)
const error = ref('')

/** 这个课件当前有没有正在直播的课堂。没有就是 null。 */
const liveSession = ref(null)
const starting = ref(false)
const actionError = ref('')

const currentPage = computed(() => pages.value[current.value] || null)

async function load() {
  loading.value = true
  error.value = ''
  actionError.value = ''
  liveSession.value = null
  try {
    const id = route.params.id
    detail.value = await http.get(`/courseware/${id}`)
    const data = await http.get(`/courseware/${id}/pages`)
    pages.value = data.list || []
    current.value = 0
    loadLive() // 不 await：直播查询慢不该拖住课件正文
  } catch (e) {
    error.value = e.message || '加载失败'
    detail.value = null
    pages.value = []
  } finally {
    loading.value = false
  }
}

/** 查这个课件有没有在直播。拿不到就当作没有，不影响浏览课件。 */
async function loadLive() {
  try {
    const data = await http.get('/session/active')
    liveSession.value =
      (data.list || []).find((s) => s.coursewareId === Number(route.params.id)) || null
  } catch {
    liveSession.value = null
  }
}

/** 教师开课：建课堂后直接进控制台。 */
async function startClass() {
  starting.value = true
  actionError.value = ''
  try {
    const data = await http.post('/session', { coursewareId: Number(route.params.id) })
    router.push(`/teach/${data.id}`)
  } catch (e) {
    actionError.value = e.message || '开课失败'
  } finally {
    starting.value = false
  }
}

function selectPage(i) {
  current.value = i
}

watch(() => route.params.id, load, { immediate: true })
</script>

<template>
  <div class="detail">
    <header class="head">
      <div class="head-main">
        <h1 class="heading">{{ detail?.name || '课件详情' }}</h1>
        <p v-if="detail" class="meta">共 {{ detail.pageCount }} 页 · 状态 {{ detail.status }}</p>
      </div>

      <div class="actions">
        <!-- 有直播时，教师和学生都该看到这个入口 -->
        <router-link v-if="liveSession" class="btn live" :to="`/live/${liveSession.id}`">
          <span class="dot" aria-hidden="true"></span>
          正在直播 · 进入课堂
        </router-link>
        <!-- 开课按钮只给教师。这只是前端体验，真正的权限边界在后端
             （POST /api/session 在 SecurityConfig 与 @PreAuthorize 上都限了 TEACHER） -->
        <button v-if="auth.isTeacher" class="btn primary" :disabled="starting" @click="startClass">
          {{ starting ? '开课中…' : '开始上课' }}
        </button>
      </div>
    </header>

    <p v-if="actionError" class="banner error" role="alert">{{ actionError }}</p>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="error" class="hint error-text">{{ error }}</div>

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
        <template v-if="currentPage">
          <h2 class="page-no">第 {{ currentPage.pageNo }} 页</h2>
          <!--
            这里用 iframe 真正渲染网页幻灯片（而不是把地址当文本打印出来）。
            ⚠️ 幻灯片不存在时后端返回 HTTP 200 + 一行 JSON，iframe 里会显示 JSON 文字；
            正常路径下页码来自 pages 列表，不会取到不存在的页。
          -->
          <iframe
            v-if="currentPage.slideUrl"
            class="slide"
            :src="currentPage.slideUrl"
            :title="`第 ${currentPage.pageNo} 页幻灯片`"
          ></iframe>
          <p v-if="currentPage.textContent" class="text">{{ currentPage.textContent }}</p>
          <p v-else class="text muted">（本页没有可抽取的文字）</p>
        </template>
        <p v-else class="hint">暂无页面</p>
      </section>
    </div>
  </div>
</template>

<style scoped>
.detail {
  max-width: 1120px;
  margin: 0 auto;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.heading {
  font-size: 22px;
  color: #333;
}

.meta {
  margin-top: 8px;
  font-size: 13px;
  color: #999;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 10px 18px;
  border-radius: 8px;
  font-size: 14px;
  cursor: pointer;
  border: 1px solid transparent;
  text-decoration: none;
  white-space: nowrap;
}

.btn.primary {
  background: #d97757;
  color: #fff;
}

.btn.primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn.live {
  background: #fff;
  color: #c0392b;
  border-color: #f3ddd4;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #e74c3c;
  animation: blink 1.4s infinite;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}

.banner {
  font-size: 13px;
  padding: 10px 14px;
  border-radius: 8px;
  margin-bottom: 14px;
}

.banner.error {
  color: #c0392b;
  background: #fdf0ee;
}

.body {
  display: flex;
  gap: 18px;
}

.page-list {
  width: 140px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 70vh;
  overflow-y: auto;
}

.page-item {
  padding: 10px 12px;
  border: 1px solid #eee;
  background: #fff;
  border-radius: 8px;
  cursor: pointer;
  text-align: left;
  font-size: 14px;
  color: #666;
}

.page-item:hover {
  border-color: #f0c8b8;
}

.page-item.active {
  border-color: #d97757;
  color: #d97757;
  font-weight: 600;
}

.preview {
  flex: 1;
  min-width: 0;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 10px;
  padding: 20px 22px;
}

.page-no {
  font-size: 15px;
  color: #333;
  margin-bottom: 14px;
}

.slide {
  width: 100%;
  height: 440px;
  border: 1px solid #eee;
  border-radius: 8px;
  background: #fff;
}

.text {
  margin-top: 16px;
  max-height: 200px;
  overflow-y: auto;
  font-size: 14px;
  line-height: 1.8;
  color: #444;
  white-space: pre-wrap;
}

.muted {
  color: #bbb;
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
</style>
