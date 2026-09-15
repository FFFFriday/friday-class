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
/**
 * **我（当前教师）**在这份课件上尚未结束的课堂。没有就是 null。
 *
 * <p>为什么要单独存一份、而不能从 liveSession 里找：老师刚开完课、还没翻第一页时
 * 状态是 NOT_STARTED，压根不在 /session/active 的结果里。只看 liveSession 的话，
 * 老师一退出控制台就再也找不到自己的课了。
 */
const mySession = ref(null)
const starting = ref(false)
const actionError = ref('')

/** 预览图加载失败时回退到纯文字版（见模板里的 @error 分支）。 */
const previewImageFailed = ref(false)

const currentPage = computed(() => pages.value[current.value] || null)

async function load() {
  loading.value = true
  error.value = ''
  actionError.value = ''
  liveSession.value = null
  mySession.value = null
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

/** 查这个课件有没有在直播、以及我自己有没有课。拿不到就当作没有，不影响浏览课件。 */
async function loadLive() {
  const coursewareId = Number(route.params.id)

  try {
    const data = await http.get('/session/active')
    // 学生视角：任何人的直播都算
    liveSession.value = (data.list || []).find((s) => s.coursewareId === coursewareId) || null
  } catch {
    liveSession.value = null
  }

  // 教师视角：还要看**自己**的课。它包含 NOT_STARTED（刚开课还没翻页），
  // 这正是老师退出控制台后能回来的唯一依据。
  if (!auth.isTeacher) {
    mySession.value = null
    return
  }
  try {
    const data = await http.get('/session/mine')
    mySession.value = (data.list || []).find((s) => s.coursewareId === coursewareId) || null
  } catch {
    // 拿不到就不显示「回到我的课堂」，但「开始上课」还在——而它本身是幂等的，
    // 所以即使这条查询失败，老师也不会因此丢失自己的课堂。
    mySession.value = null
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
  // 换页要清掉回退标记，否则某一页渲染失败后，后面每一页都会一直是文字版
  previewImageFailed.value = false
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
        <!--
          教师：我自己在这份课件上的课堂，排在第一位。
          这是老师退出控制台后唯一的回头路，而且它可能还没开始翻页（NOT_STARTED），
          那时不会出现在「正在直播」里，所以必须单独查一次 /session/mine。
        -->
        <router-link v-if="mySession" class="btn primary" :to="`/teach/${mySession.id}`">
          <span v-if="mySession.status === 'LIVE'" class="dot" aria-hidden="true"></span>
          {{ mySession.status === 'LIVE' ? '回到我的课堂' : '回到我的课堂（未开始）' }}
        </router-link>

        <!-- 不是我的那节课：学生看到的入口；教师也能点进去旁观别人的课 -->
        <router-link
          v-if="liveSession && liveSession.id !== mySession?.id"
          class="btn live"
          :to="`/live/${liveSession.id}`"
        >
          <span class="dot" aria-hidden="true"></span>
          {{ auth.isTeacher ? '其他老师的直播' : '正在直播 · 进入课堂' }}
        </router-link>

        <!-- 开课按钮：只给教师，且自己在这份课件上没有进行中的课堂时才出现。
             真正的权限边界在后端（POST /api/session 在 SecurityConfig 与 @PreAuthorize
             上都限了 TEACHER），而且 Service 里对「同一课件已有未结束课堂」做了幂等复用，
             所以万一这个按钮还是被点了，也只会回到原来那节课，不会多开一节。 -->
        <button
          v-if="auth.isTeacher && !mySession"
          class="btn primary"
          :disabled="starting"
          @click="startClass"
        >
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
            这里显示的是后端真正渲染出来的整页 PPT 图片（图片、配色、排版都还原），
            不是把文字倒进白底 div 的那份 HTML。后者仍保留为图片失败时的兜底。
          -->
          <img
            v-if="!previewImageFailed && detail"
            class="slide"
            :src="`/slides/${detail.id}/page${currentPage.pageNo}.png`"
            :alt="`第 ${currentPage.pageNo} 页幻灯片`"
            @error="previewImageFailed = true"
          />
          <iframe
            v-else-if="currentPage.slideUrl"
            class="slide"
            :src="currentPage.slideUrl"
            :title="`第 ${currentPage.pageNo} 页幻灯片（文字版）`"
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
  /* PPT 是 16:9。写死高度会把它压变形 */
  aspect-ratio: 16 / 9;
  object-fit: contain;
  border: 1px solid #eee;
  border-radius: 8px;
  background: #fff;
  display: block;
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
