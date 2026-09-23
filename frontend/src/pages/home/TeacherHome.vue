<script setup>
// 教师首页：慕课分节型（欢迎横幅 + 多节横向卡片流）。
//
// 数据来源只有一个真实接口 GET /api/courseware，没有别的。
// 「我的课件」是前端按 uploaderId 从列表里筛出来的——后端 list 接口目前
// 只支持 keyword / status 两个筛选条件，没有 uploaderId 参数。
// ⚠️ 已知局限：一次只取前 FETCH_SIZE 条来筛，课件总数超过这个数时
//    「我的课件」会漏。正解是后端给 list 加一个 mine/uploaderId 参数（另开任务）。
import { computed, onMounted, ref } from 'vue'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import CoursewareCard from '@/components/CoursewareCard.vue'
import LiveSessions from '@/components/LiveSessions.vue'
import StartClassModal from '@/components/features/StartClassModal.vue'

const auth = useAuthStore()

const FETCH_SIZE = 60 // 见上方局限说明
const SECTION_SIZE = 4 // 每节展示一行

const all = ref([])
const loading = ref(false)
const error = ref('')

const mine = computed(() =>
  all.value.filter((c) => c.uploaderId != null && c.uploaderId === auth.user?.id),
)
const mySection = computed(() => mine.value.slice(0, SECTION_SIZE))
const allSection = computed(() => all.value.slice(0, SECTION_SIZE))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/courseware', { params: { page: 1, size: FETCH_SIZE } })
    all.value = data.list || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

// ── 课堂记录（只放**已结束**的课）───────────────────────────
//
// 「老师要能回顾已经上完的课」在过去是彻底断的：全项目唯一的回顾入口在直播页里，
// 老师一下课、一离开那个页面，就再也找不回来了。
//
// 数据源 /session/taught（含已结束）。不能用 /session/mine ——
// 那个只返回未结束的，正是「回顾找不到入口」的原因。
//
// ⚠️ 这里**只放已结束的**。进行中（含未开始）的课由上面的 <LiveSessions> 负责：
// 它那块「我的课堂」用的就是 /session/mine 全量、并自带「回到控制台」入口。
// 以前首页又自己渲染了一遍进行中的课，两块内容重复。
const taught = ref([])

const endedTaught = computed(() => taught.value.filter((s) => s.status === 'ENDED'))
/** 首页只展示最近几条，全量在「全部记录」页 /teacher/records */
const endedSection = computed(() => endedTaught.value.slice(0, 4))

const STATUS_TEXT = { NOT_STARTED: '未开始', LIVE: '直播中', PAUSED: '已暂停', ENDED: '已结束' }

async function loadTaught() {
  try {
    const data = await http.get('/session/taught')
    taught.value = data.list || []
  } catch {
    // 拿不到就整块不显示，不该让课件区也打不开
    taught.value = []
  }
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : ''
}

// ── 开课 ────────────────────────────────────────────────────
//
// 两个入口共用一个弹窗（StartClassModal，与课件详情页共用同一份逻辑）：
//   · 大卡片的「开始上课」—— 课件没定，弹窗里先让老师选一份；
//   · 卡片右下角的「上课」—— 课件已定，直接跳到「给谁上」那一步。
const startOpen = ref(false)
/** 非空 = 从某张卡片进来的（课件已定）；null = 从大卡片进来的（要先选课件）。 */
const startCoursewareId = ref(null)

function openStart(courseware = null) {
  startCoursewareId.value = courseware?.id ?? null
  startOpen.value = true
}

onMounted(() => {
  load()
  loadTaught()
})
</script>

<template>
  <div class="page">
    <section class="banner">
      <div class="banner-inner">
        <h1 class="banner-title">你好，{{ auth.displayName }}</h1>
        <p class="banner-sub">今天想上哪一节课？</p>
        <!-- 按修改文档3 定的三个按钮，顺序是「要干的事」在前：
             开始上课 → 班级管理（备课）→ 上传课件（备料）。 -->
        <div class="banner-actions">
          <button class="btn solid" type="button" @click="openStart()">开始上课</button>
          <router-link class="btn ghost" to="/teacher/classes">班级管理</router-link>
          <router-link class="btn ghost" to="/upload">上传课件</router-link>
        </div>
      </div>
    </section>

    <!-- 正在上课 / 我的课堂（进行中）。有课在进行时排在最上面，比课件列表更紧急；
         没有课时整块不渲染。进行中的课**只在这里出现一次**。 -->
    <LiveSessions />

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="error" class="hint error-text">{{ error }}</div>

    <template v-else>
      <section class="section">
        <header class="section-head">
          <h2 class="section-title">我的课件</h2>
          <router-link class="more" to="/courseware">查看全部 →</router-link>
        </header>
        <div v-if="mySection.length" class="row">
          <!-- 卡片右下角的「上课」：课件已定，点完直接到「给谁上」那一步 -->
          <CoursewareCard v-for="c in mySection" :key="c.id" :courseware="c">
            <template #action>
              <button class="btn-mini btn-mini--primary" type="button" @click="openStart(c)">
                上课
              </button>
            </template>
          </CoursewareCard>
        </div>
        <p v-else class="empty">
          你还没有上传过课件。<router-link class="link" to="/upload">现在上传第一个 →</router-link>
        </p>
      </section>

      <section class="section">
        <header class="section-head">
          <h2 class="section-title">全部课件</h2>
          <router-link class="more" to="/courseware">查看全部 →</router-link>
        </header>
        <!-- 这里**不加**上课按钮：课件中心也是同样的样子（修改文档3 明说了要一致） -->
        <div v-if="allSection.length" class="row">
          <CoursewareCard v-for="c in allSection" :key="c.id" :courseware="c" />
        </div>
        <p v-else class="empty">平台上还没有任何课件。</p>
      </section>

      <!-- 课堂记录：只放**已结束**的课。进行中的在上面 <LiveSessions> 里，不重复 -->
      <section v-if="endedSection.length" class="section">
        <header class="section-head">
          <h2 class="section-title">课堂记录</h2>
          <router-link class="more" to="/teacher/records">全部记录 →</router-link>
        </header>

        <ul class="lessons">
          <li v-for="s in endedSection" :key="s.id" class="lesson">
            <span class="lesson__title">{{ s.title || '未命名课堂' }}</span>
            <span class="lesson__meta">{{ formatTime(s.endedAt) }}</span>
            <span class="lesson__status">{{ STATUS_TEXT[s.status] }}</span>
            <!-- 这一条就是问题点 9 缺的那个入口：老师不下直播页也能进回顾 -->
            <router-link class="btn-mini" :to="{ name: 'session-record', params: { sessionId: s.id } }">
              回顾
            </router-link>
          </li>
        </ul>

        <p v-if="endedTaught.length > endedSection.length" class="lesson__more">
          还有 {{ endedTaught.length - endedSection.length }} 节已结束的课，点右上角看全部。
        </p>
      </section>
    </template>

    <!-- 开课弹窗。从大卡片「开始上课」进来时 startCoursewareId 是 null → 弹窗先让选课件；
         从卡片「上课」进来时带了 id → 直接到「给谁上」。 -->
    <StartClassModal v-model="startOpen" :coursewares="mine" :courseware-id="startCoursewareId" />
  </div>
</template>

<style scoped>
.page {
  max-width: 1120px;
  margin: 0 auto;
}

.banner {
  border-radius: 14px;
  padding: 40px 44px;
  margin-bottom: 34px;
  background: linear-gradient(120deg, #d97757 0%, #e08d6a 55%, #eeae91 100%);
  color: #fff;
}

.banner-title {
  font-size: 28px;
  font-weight: 700;
}

.banner-sub {
  margin-top: 10px;
  font-size: 15px;
  opacity: 0.92;
}

.banner-actions {
  margin-top: 22px;
  display: flex;
  gap: 12px;
}

/* 三个按钮里「开始上课」是 <button>，另两个是 <router-link>。
   这里把两者的外观抹平：按钮要显式清掉浏览器默认的边框与背景、继承页面字体、
   再给出手型光标，否则它看起来会和旁边两个链接不是一套东西。 */
.btn {
  display: inline-block;
  padding: 10px 22px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font: inherit;
  font-size: 14px;
  text-decoration: none;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

/* 白底橙字，压在橙色横幅上对比度足够 */
.btn.solid {
  background: #fff;
  color: #c9694a;
  font-weight: 600;
}

.btn.solid:hover {
  background: #fdf1ec;
}

/* 描边按钮：半透明白边，比实心按钮弱一级 */
.btn.ghost {
  border: 1px solid rgba(255, 255, 255, 0.75);
  color: #fff;
}

.btn.ghost:hover {
  background: rgba(255, 255, 255, 0.15);
}

.section {
  margin-bottom: 34px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-title {
  font-size: 18px;
  color: #333;
}

.more {
  font-size: 13px;
  color: #999;
  text-decoration: none;
}

.more:hover {
  color: #d97757;
}

.row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.empty {
  padding: 22px 0;
  font-size: 14px;
  color: #999;
}

.link {
  color: #d97757;
  text-decoration: none;
}

/* ── 我的课堂 ─────────────────────────────────────────────── */
.lessons {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.lesson {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border: 1px solid #eee;
  border-radius: 10px;
  background: #fff;
}

.lesson__title {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lesson__meta {
  font-size: 12px;
  color: #bbb;
}

.lesson__status {
  flex-shrink: 0;
  font-size: 12px;
  color: #999;
}

.lesson__more {
  margin-top: 10px;
  font-size: 12px;
  color: #999;
}

.btn-mini {
  flex-shrink: 0;
  padding: 5px 12px;
  border: 1px solid #ddd;
  border-radius: 7px;
  background: #fff;
  font-size: 12px;
  color: #333;
  text-decoration: none;
}

.btn-mini:hover {
  border-color: #d97757;
  color: #d97757;
}

.btn-mini--primary {
  border-color: #d97757;
  background: #d97757;
  color: #fff;
}

.btn-mini--primary:hover {
  background: #c9694a;
  color: #fff;
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

/* 窄屏收成两列，避免四列卡片被挤成竖条 */
@media (max-width: 900px) {
  .row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .banner {
    padding: 28px 24px;
  }
}
</style>
