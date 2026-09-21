<script setup>
// 学生首页：**主区是「我的课堂」**（问题点 5），课件浏览降为次级入口。
//
// 改造前这里是「浏览全部课件」，当时的注释解释得很实在：
// 「数据库里没有选课表，硬做『我的课程』只能编假数据」。
// 现在有了 —— V3 迁移加了班级体系，开课时会把听课名单快照进 session_audience，
// 所以「我能上哪些课」是一个**真实存在、可查询**的概念，不再是编的。
//
// 可见性口径（后端 SessionAccessService，前端只消费不判断）：
//   公开课谁都能看；限定课只有名单里的人能看。
// 因此「我的课堂」= 公开课 + 我被点名/所在班级的课。
//
// ⚠ 副作用要知道：历史课堂被统一设为 PUBLIC（周五决策 3），
//   所以这里会列出全部历史课。不是 bug，是那条决策的直接结果。
import { computed, onMounted, ref } from 'vue'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import CoursewareCard from '@/components/CoursewareCard.vue'
import LiveSessions from '@/components/LiveSessions.vue'

const auth = useAuthStore()

// ── 我的课堂 ───────────────────────────────────────────────
const sessions = ref([])
const sessionsLoading = ref(false)
const sessionsError = ref('')

const STATUS_TEXT = {
  NOT_STARTED: '等待开始',
  LIVE: '直播中',
  PAUSED: '已暂停',
  ENDED: '已结束',
}

async function loadSessions() {
  sessionsLoading.value = true
  sessionsError.value = ''
  try {
    const data = await http.get('/session/my-sessions')
    sessions.value = data.list || []
  } catch (e) {
    sessionsError.value = e.message || '加载失败'
  } finally {
    sessionsLoading.value = false
  }
}

/** 进行中（能进去）的课排在前面，已结束的排后面。后端已经排过一次，这里只做展示分组。 */
const liveSessions = computed(() =>
  sessions.value.filter((s) => s.status === 'LIVE' || s.status === 'PAUSED'),
)
const endedSessions = computed(() => sessions.value.filter((s) => s.status === 'ENDED'))

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : ''
}

// ── 全部课件（次级入口，F001 的浏览能力不能砍）─────────────
const list = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const keyword = ref('')
const loading = ref(false)
const error = ref('')
/** 默认折叠：首页的主区是「我的课堂」，课件浏览是备用路径。 */
const coursewareOpen = ref(false)

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

function toggleCourseware() {
  coursewareOpen.value = !coursewareOpen.value
  // 第一次展开才加载，省掉首页一次用不上的请求
  if (coursewareOpen.value && !list.value.length) load()
}

onMounted(() => {
  loadSessions()
})
</script>

<template>
  <div class="page">
    <section class="head">
      <h1 class="title">你好，{{ auth.displayName }}</h1>
      <p class="sub">你所在班级的课都在这里</p>
    </section>

    <!-- 正在直播：进首页第一眼就能看到「现在有课」。没有课时整块不渲染 -->
    <LiveSessions />

    <!-- ── 主区：我的课堂 ───────────────────────────────── -->
    <header class="section-head">
      <h2 class="section-title">我的课堂</h2>
      <span v-if="sessions.length" class="count">共 {{ sessions.length }} 节</span>
    </header>

    <div v-if="sessionsLoading" class="hint">加载中…</div>
    <div v-else-if="sessionsError" class="hint error-text">{{ sessionsError }}</div>

    <template v-else>
      <div v-if="!sessions.length" class="hint">
        还没有你能看到的课堂。等老师建班并把你加进去，或者让老师把课设为公开。
      </div>

      <ul v-else class="sessions">
        <li v-for="s in sessions" :key="s.id" class="session">
          <div class="session__main">
            <span class="session__title">{{ s.title || '未命名课堂' }}</span>
            <span class="session__meta">
              {{ s.teacherName || '—' }} · {{ s.coursewareName || '课件已删除' }}
              <template v-if="s.classGroupNames && s.classGroupNames.length">
                · {{ s.classGroupNames.join(' / ') }}
              </template>
            </span>
          </div>

          <span class="session__status" :class="`session__status--${s.status}`">
            {{ STATUS_TEXT[s.status] || s.status }}
          </span>

          <!-- 进行中 → 进直播间；已结束 → 进回顾页。
               等待开始的不给按钮：老师还没开讲，学生进去也是空的 -->
          <RouterLink
            v-if="s.status === 'LIVE' || s.status === 'PAUSED'"
            class="btn btn--primary"
            :to="{ name: 'live', params: { sessionId: s.id } }"
          >
            进入课堂
          </RouterLink>
          <RouterLink
            v-else-if="s.status === 'ENDED'"
            class="btn"
            :to="{ name: 'session-record', params: { sessionId: s.id } }"
          >
            {{ s.hasSummary ? '回顾' : '回顾（尚无总结）' }}
          </RouterLink>
          <span v-else class="session__wait">等老师开始</span>
        </li>
      </ul>

      <p v-if="liveSessions.length && endedSessions.length" class="foot-note">
        已结束的课点「回顾」可以看讨论区、自己的提问与课后总结。
      </p>
    </template>

    <!-- ── 次级：全部课件 ───────────────────────────────── -->
    <header class="section-head section-head--sub">
      <h2 class="section-title">全部课件</h2>
      <button class="toggle" type="button" @click="toggleCourseware">
        {{ coursewareOpen ? '收起' : '展开浏览' }}
      </button>
    </header>

    <template v-if="coursewareOpen">
      <form class="search-form" @submit.prevent="search">
        <input
          v-model="keyword"
          class="search"
          type="search"
          aria-label="搜索课件"
          placeholder="搜索课件名称，回车开始…"
        />
      </form>

      <div v-if="loading" class="hint">加载中…</div>
      <div v-else-if="error" class="hint error-text">{{ error }}</div>
      <template v-else>
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

/* 次级区与主区之间用一条线隔开，视觉上明确「这不是重点」 */
.section-head--sub {
  margin-top: 44px;
  padding-top: 26px;
  border-top: 1px solid #eee;
}

.toggle {
  background: none;
  border: none;
  padding: 0;
  font-size: 13px;
  color: #d97757;
  cursor: pointer;
}

.count {
  font-size: 13px;
  color: #999;
}

.sessions {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.session {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid #eee;
  border-radius: 10px;
  background: #fff;
}

.session__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.session__title {
  font-size: 15px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session__meta {
  font-size: 12px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session__status {
  flex-shrink: 0;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 999px;
  background: #f2f2f2;
  color: #777;
}

.session__status--LIVE {
  background: #fdecea;
  color: #e74c3c;
}

.session__status--PAUSED {
  background: #fff5e6;
  color: #c98a2b;
}

.session__wait {
  flex-shrink: 0;
  font-size: 12px;
  color: #bbb;
}

.btn {
  flex-shrink: 0;
  padding: 6px 14px;
  border: 1px solid #ddd;
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: #333;
  text-decoration: none;
  cursor: pointer;
}

.btn:hover {
  border-color: #d97757;
  color: #d97757;
}

.btn--primary {
  border-color: #d97757;
  background: #d97757;
  color: #fff;
}

.btn--primary:hover {
  background: #c9694a;
  color: #fff;
}

.foot-note {
  margin-top: 14px;
  font-size: 12px;
  color: #999;
}

.search-form {
  margin-bottom: 18px;
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

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 18px;
}

.hint {
  text-align: center;
  color: #999;
  padding: 40px 0;
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
