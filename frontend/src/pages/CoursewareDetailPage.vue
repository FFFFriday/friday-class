<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useAiParse } from '@/composables/useAiParse'
import { usePromptPack } from '@/composables/usePromptPack'

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

/**
 * 当前课件 ID。
 *
 * 传成**函数**而不是值：同一个组件实例会被复用来显示不同课件
 * （路由参数变、组件不重建），组合式函数里存一份快照就会一直查旧课件。
 */
function currentCoursewareId() {
  return Number(route.params.id)
}

// ── AI 解析（F002）────────────────────────────────────────────────
// 状态与轮询都收在组合式函数里，与教师的直播控制台共用同一套。
const {
  loaded: parseLoaded,
  triggering: parseTriggering,
  error: parseError,
  status: parseStatus,
  running: parseRunning,
  percent: parsePercent,
  everParsed: parseEverParsed,
  needsAttention: parseNeedsAttention,
  statusLabel: parseStatusLabel,
  hint: parseHint,
  refresh: refreshParse,
  trigger: triggerParse,
} = useAiParse(currentCoursewareId, { onSettled: onParseSettled })

// ── 提示词包：AI 解析出来的知识点与预置提问 ────────────────────────
const { pagePack, load: loadPack } = usePromptPack(currentCoursewareId)

/** 当前页在提示词包里的那一条。没解析过、或本页确实没内容时是 null。 */
const currentPack = computed(() => pagePack(currentPage.value?.id ?? null))
const currentKnowledge = computed(() => currentPack.value?.knowledgePoints || [])
const currentPresets = computed(() => currentPack.value?.presetQuestions || [])

/** 解析收尾时刷新「解析的出产物」。轮询自己不知道要刷什么，所以由页面接这里。 */
async function onParseSettled() {
  await Promise.all([loadPack(), refreshDetail()])
}

/** 只刷新课件本身（状态会从 PARSING 变成 PARSED）。不碰 pages，老师选的页码不会被重置。 */
async function refreshDetail() {
  try {
    detail.value = await http.get(`/courseware/${currentCoursewareId()}`)
  } catch {
    // 状态刷新失败不该清掉已经打开的课件
  }
}

async function load() {
  loading.value = true
  error.value = ''
  actionError.value = ''
  liveSession.value = null
  mySession.value = null
  try {
    const id = currentCoursewareId()
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

  // 解析状态与知识点放在正文之后、且**不 await**：
  // 它们慢或者失败，都不该让课件正文打不开——知识点属于附加信息。
  refreshParse()
  loadPack()
}

/** 查这个课件有没有在直播、以及我自己有没有课。拿不到就当作没有，不影响浏览课件。 */
async function loadLive() {
  const coursewareId = currentCoursewareId()

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
    const data = await http.post('/session', { coursewareId: currentCoursewareId() })
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

    <!--
      AI 解析（F002）。只给教师看：后端 POST /api/courseware/{id}/parse 上
      同时有 SecurityConfig 与 @PreAuthorize 两道 TEACHER 限制，这里只是不让学生
      看到一个点了必然 403 的按钮。
      放在正文上方，是因为「这份课件到底有没有被 AI 读懂」是老师开课**之前**
      就该确认的事——学生提问全部答不出内容，根因往往就在这里。
    -->
    <section v-if="auth.isTeacher" class="parse">
      <div class="parse-top">
        <span class="parse-name">AI 解析</span>
        <span
          class="parse-state"
          :class="{
            ok: parseStatus === 'SUCCESS',
            warn: parseNeedsAttention,
            run: parseRunning,
          }"
        >
          {{ parseLoaded ? parseStatusLabel : '查询中…' }}
        </span>
      </div>

      <div
        v-if="parseRunning"
        class="bar"
        role="progressbar"
        :aria-valuenow="parsePercent"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <div class="bar-fill" :style="{ width: parsePercent + '%' }"></div>
      </div>

      <p v-if="parseHint" class="parse-hint" :class="{ warn: parseNeedsAttention }">
        {{ parseHint }}
      </p>
      <p v-if="parseError" class="parse-hint warn" role="alert">{{ parseError }}</p>

      <div class="parse-actions">
        <button
          class="btn primary"
          :disabled="parseTriggering || parseRunning || !pages.length"
          @click="triggerParse"
        >
          {{
            parseTriggering
              ? '提交中…'
              : parseRunning
                ? '解析中…'
                : parseEverParsed
                  ? '重新解析'
                  : '开始 AI 解析'
          }}
        </button>
        <span v-if="!loading && !pages.length" class="parse-tip">
          这份课件没有页面，无法解析
        </span>
        <span v-else-if="parseEverParsed && !parseRunning" class="parse-tip">
          重新解析会覆盖旧的解析结果
        </span>
      </div>
    </section>

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

          <!--
            这一页 AI 到底读出了什么。老师需要它来验收解析质量：
            知识点跑偏（比如把页脚当成知识点）时，只有在这里才看得出来。
            学生端**不显示**知识点——那是 AI 助手的回答素材，直接摊开就没必要问了。
          -->
          <template v-if="auth.isTeacher">
            <div v-if="currentKnowledge.length || currentPresets.length" class="kp">
              <div v-if="currentKnowledge.length" class="kp-block">
                <p class="kp-title">AI 提炼的知识点</p>
                <ul class="kp-list">
                  <li v-for="(k, i) in currentKnowledge" :key="i">{{ k }}</li>
                </ul>
              </div>
              <div v-if="currentPresets.length" class="kp-block">
                <p class="kp-title">AI 预置的提问</p>
                <ul class="kp-list">
                  <li v-for="(q, i) in currentPresets" :key="i">{{ q }}</li>
                </ul>
              </div>
            </div>
            <p v-else-if="parseRunning" class="kp-empty">本页正在解析中…</p>
            <p v-else-if="parseEverParsed" class="kp-empty">
              本页没有解析出内容。封面页、目录页、纯图片页会这样，属正常情况。
            </p>
            <p v-else class="kp-empty">
              这份课件还没有 AI 解析，点上面的按钮跑一次，就能看到每页的知识点。
            </p>
          </template>
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

/* ── AI 解析面板 ─────────────────────────────────────────────── */
.parse {
  background: #fff;
  border: 1px solid #eee;
  border-radius: 10px;
  padding: 16px 18px;
  margin-bottom: 16px;
}

.parse-top {
  display: flex;
  align-items: center;
  gap: 10px;
}

.parse-name {
  font-size: 14px;
  color: #333;
  font-weight: 600;
}

.parse-state {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 20px;
  color: #888;
  background: #f5f5f5;
}

.parse-state.ok {
  color: #27ae60;
  background: #eafaf1;
}

.parse-state.run {
  color: #d97757;
  background: #fff3e6;
}

.parse-state.warn {
  color: #a06000;
  background: #fff7e6;
}

.bar {
  margin-top: 12px;
  height: 6px;
  border-radius: 3px;
  background: #f0f0f0;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: #d97757;
  /* 进度是每页跳一次的，加过渡让它看起来是「在走」而不是「在跳」 */
  transition: width 0.4s ease;
}

.parse-hint {
  margin-top: 10px;
  font-size: 13px;
  color: #999;
  line-height: 1.7;
}

.parse-hint.warn {
  color: #a06000;
}

.parse-actions {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.parse-tip {
  font-size: 12px;
  color: #bbb;
}

/* ── 当前页的知识点 / 预置提问 ───────────────────────────────── */
.kp {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed #eee;
  display: flex;
  gap: 28px;
  flex-wrap: wrap;
}

.kp-block {
  flex: 1;
  min-width: 240px;
}

.kp-title {
  font-size: 13px;
  color: #d97757;
  margin-bottom: 8px;
}

.kp-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.kp-list li {
  font-size: 13px;
  color: #555;
  line-height: 1.7;
  padding-left: 14px;
  position: relative;
}

.kp-list li::before {
  content: '';
  position: absolute;
  left: 2px;
  top: 9px;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: #e5c3b3;
}

.kp-empty {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed #eee;
  font-size: 13px;
  color: #bbb;
  line-height: 1.7;
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
