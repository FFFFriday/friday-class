<script setup>
// 学生端课堂页：左边是「与老师同步的幻灯片」，右边是 AI 问答。
//
// 翻页同步走 WebSocket（见 composables/usePageSync）：
// HTTP 是「客户端问、服务端答」，服务端没有嘴，没法主动告诉学生「老师翻页了」。
// 所以需要一条服务端能主动往下推的长连接。
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { usePageSync } from '@/composables/usePageSync'
import { usePromptPack } from '@/composables/usePromptPack'

const route = useRoute()

const session = ref(null)
const pages = ref([])
const records = ref([])
const question = ref('')

// 三个错误分开存：一次提问失败不该把直播画面和问答列表一起清掉
const loadError = ref('')
const askError = ref('')
const sending = ref(false)
const loading = ref(true)

/**
 * 历史记录读不到时的提示。
 *
 * 这里原来是个 `qaUnavailable` 开关，语义是「F004 还没实现，接口 404，把问答区标成开发中」。
 * F004 已经上线，那个语义没有了；但**读历史失败**仍然会发生（网络抖动、令牌刚好过期），
 * 而且它**不该禁用提问**——读不到旧记录，不代表发不出新问题。
 * 所以它降级成一句提示，不再参与 canAsk。
 */
const recordsError = ref('')

/**
 * 限流冷却倒计时（秒）。大于 0 时禁用发送。
 *
 * 为什么是倒计时而不只是一句红字：429 不是「出错了」，是「等几秒就好」。
 * 只弹红字的话学生不知道要等多久，会反复点、反复收到同一句话；
 * 把剩余时间写在按钮上，他看一眼就知道该等。
 */
const cooldownLeft = ref(0)
let cooldownUntil = 0
let cooldownTimer = null

/** 与后端 QaService.MIN_ASK_INTERVAL_MS（5 秒）对齐，多给 0.5 秒吸收两端时钟误差。 */
const RATE_LIMIT_COOLDOWN_MS = 5500

/** 后端业务码 429「提问太快了」。见 api/http.js 里 bizCode 的来源。 */
const BIZ_RATE_LIMITED = 429

function tickCooldown() {
  const left = cooldownUntil - Date.now()
  cooldownLeft.value = left > 0 ? Math.ceil(left / 1000) : 0
  if (left <= 0) stopCooldown()
}

function startCooldown(ms) {
  cooldownUntil = Date.now() + ms
  tickCooldown()
  if (!cooldownTimer) cooldownTimer = setInterval(tickCooldown, 250)
}

function stopCooldown() {
  if (cooldownTimer) {
    clearInterval(cooldownTimer)
    cooldownTimer = null
  }
  cooldownUntil = 0
  cooldownLeft.value = 0
}

onUnmounted(stopCooldown)

/**
 * 「本页暂无解析内容」这类临时提示。
 *
 * 后端的 SKIPPED 响应**没有落库**（`id` 为 null）。把它追加进问答列表会有两个问题：
 * 刷新页面它就消失了（看起来像记录丢了），而它本来也不在数据库里。
 * 所以单独存一份，只当提示显示。
 */
const skippedNotice = ref('')

/** 这份直播用的课件 ID。课堂加载完才有值。 */
const coursewareId = computed(() => session.value?.coursewareId ?? null)

/**
 * 提示词包：一次拉全所有页的知识点与预置提问，按 pageId 建索引。
 *
 * `watchParsing: true` 是必需的——学生常在老师刚点完解析就进课堂，
 * 那一刻包里还是空的。不盯着解析进度自动重拉的话，学生会一直看到
 * 「本页暂无解析内容」，而实际上解析十几秒后就跑完了。
 */
const { pagePack, load: loadPack } = usePromptPack(() => coursewareId.value, {
  watchParsing: true,
})

const {
  currentPage: broadcastPage,
  connected,
  lastError,
  ended: broadcastEnded,
} = usePageSync(
  route.params.sessionId,
  () => {
    askError.value = ''
  },
)

/** 广播优先；还没收到广播时用接口里的 currentPage 兜底；都没有则是第 1 页。 */
const currentPage = computed(() => broadcastPage.value ?? session.value?.currentPage ?? 1)

/** 已结束 = 接口查出来是 ENDED，或刚收到下课广播。 */
const ended = computed(() => broadcastEnded.value || session.value?.status === 'ENDED')

/** 整页 PPT 图片（后端渲染的真课件画面）。课堂还没加载出来时是 null。 */
const slideImageUrl = computed(() =>
  coursewareId.value ? `/slides/${coursewareId.value}/page${currentPage.value}.png` : null,
)

/** 纯文字版地址，图片加载失败时的兜底。 */
const slideTextUrl = computed(() =>
  coursewareId.value ? `/slides/${coursewareId.value}/page${currentPage.value}.html` : null,
)

const imageFailed = ref(false)

// 换页要清掉回退标记，否则某一页渲染失败后，后面的页也会一直被锁在文字版
watch(currentPage, () => {
  imageFailed.value = false
})

/**
 * 页码 → 页 ID 的映射。
 *
 * ⚠️ 提问必须带 **pageId**（不是 pageNo）：AI 靠它取本页知识点。
 * 原来这里写死 `pageId: 1`，导致无论老师翻到第几页，AI 都拿第 1 页的内容回答。
 * 也不能用 pageNo 当缓存键——两份课件的「第 1 页」是两个不同的 pageId。
 */
const pageIdByNo = computed(() => {
  const map = {}
  for (const p of pages.value) {
    map[p.pageNo] = p.id
  }
  return map
})

const currentPageId = computed(() => pageIdByNo.value[currentPage.value] ?? null)

/** 本页 AI 预置的「思考题」。还没解析、或本页确实没内容时是空数组。 */
const currentPresets = computed(() => pagePack(currentPageId.value)?.presetQuestions || [])

const canAsk = computed(
  () =>
    currentPageId.value !== null &&
    !sending.value &&
    // 限流冷却期内直接禁用，而不是等学生点下去再报一次「问太快了」
    cooldownLeft.value === 0 &&
    // 课都下课了就别再让学生提问了：AI 拿着本页知识点答一道已经结束的课的问题，
    // 既没人看，也会在课后总结里混进噪声
    !ended.value,
)

/**
 * 幂等键。同一次「提问意图」在重试时必须用同一个键。
 *
 * 后端按 `client_request_id` 去重：键一样就**返回上次那条记录**，
 * 不会重复调模型、也不会多写一行。这正是「请求超时了，但服务端其实已经答完并落库」
 * 那种情况下最省钱的一道保险。
 *
 * ⚠️ 记的是「意图」而不只是一个键：键必须跟着**页码 + 问题**走。
 * 只存一个键的话，学生在第 3 页提问超时、翻到第 5 页再问别的，
 * 复用旧键就会拿到第 3 页那条记录 —— 答非所问，且完全看不出哪里错了。
 */
const pendingAsk = ref({ key: null, pageId: null, text: '' })

function newRequestId() {
  // randomUUID 需要安全上下文（https 或 localhost）。dev 下 localhost:5173 满足，
  // 但万一改用 IP 访问就没有了，所以留个降级分支而不是让它直接抛错。
  if (globalThis.crypto?.randomUUID) return crypto.randomUUID()
  return `req-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

async function load() {
  loading.value = true
  loadError.value = ''
  askError.value = ''
  recordsError.value = ''
  skippedNotice.value = ''
  // 换课堂要清掉冷却：那是上一个课堂的限流状态，跟这一节没关系
  stopCooldown()
  try {
    const sessionId = route.params.sessionId
    session.value = await http.get(`/session/${sessionId}`)

    const pageList = await http.get(`/courseware/${session.value.coursewareId}/pages`)
    pages.value = pageList.list || []

    // 知识点单独拉，失败也不影响课堂：翻页同步和提问都还能用
    loadPack()

    try {
      const recordList = await http.get('/qa/records', { params: { sessionId } })
      records.value = recordList.list || []
    } catch (e) {
      // 读历史失败只给提示，**不禁用提问**：读不到旧记录，不代表发不出新问题。
      // （以前这里会把整个问答区标成不可用，一次网络抖动就让学生整节课问不了。）
      recordsError.value = e.message || '问答记录暂时读不到'
    }
  } catch (e) {
    loadError.value = e.message || '加载失败'
    session.value = null
  } finally {
    loading.value = false
  }
}

/** 点预置思考题：只填进输入框，**不直接发出去**——避免手滑白花一次模型调用。 */
function usePreset(text) {
  question.value = text
}

async function ask() {
  const text = question.value.trim()
  if (!text || !canAsk.value) return

  const pageId = currentPageId.value

  // 换了页或改了问题 = 一次新的提问意图，换新键；
  // 否则沿用上次失败的那个键，让后端去重（详见 pendingAsk 的注释）。
  if (pendingAsk.value.pageId !== pageId || pendingAsk.value.text !== text) {
    pendingAsk.value = { key: newRequestId(), pageId, text }
  }

  sending.value = true
  askError.value = ''
  skippedNotice.value = ''
  try {
    const data = await http.post('/qa/ask', {
      sessionId: Number(route.params.sessionId),
      pageId,
      question: text,
      clientRequestId: pendingAsk.value.key,
    })

    if (data.status === 'SKIPPED') {
      // 没调模型、也没落库（id 为 null）。当临时提示显示，**不要**塞进列表。
      skippedNotice.value = data.answer
    } else {
      // SUCCESS 和 FAILED 都进列表：FAILED 也是一次真实问答，
      // 老师/统计要能看出「这个问题 AI 没答上」，藏起来反而失真。
      records.value.push({ ...data, pageNo: currentPage.value })
    }

    question.value = ''
    pendingAsk.value = { key: null, pageId: null, text: '' }
  } catch (e) {
    // 出错时**故意保留** pendingAsk：学生再点一次「发送」会复用同一个键，
    // 「超时但其实已落库」的情况下就不会重复计费
    if (e.bizCode === BIZ_RATE_LIMITED) {
      // 限流不是错误，是「稍等」，所以不占红字报错区，改用按钮倒计时表达
      startCooldown(RATE_LIMIT_COOLDOWN_MS)
    } else {
      askError.value = e.message || '发送失败'
    }
  } finally {
    sending.value = false
  }
}

watch(() => route.params.sessionId, load, { immediate: true })
</script>

<template>
  <div class="live">
    <header class="head">
      <h1 class="title">{{ session?.title || '直播课堂' }}</h1>
      <div class="tags">
        <span v-if="session" class="tag page">第 {{ currentPage }} 页</span>
        <span v-if="ended" class="tag ended-tag">已结束</span>
        <span v-else class="tag" :class="connected ? 'on' : 'off'">
          {{ connected ? '实时同步中' : '重连中…' }}
        </span>
      </div>
    </header>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="loadError" class="hint error-text">{{ loadError }}</div>

    <div v-else class="body">
      <section class="stage">
        <!-- 下课了要明说，否则学生盯着最后一页不知道是自己卡了还是课上完了 -->
        <p v-if="ended" class="ended-banner" role="status">
          本节课已结束，感谢参与。
        </p>

        <!--
          学生看的是「和老师同步的那一页幻灯片」——现在是后端渲染好的整页 PPT 图片，
          图片、配色、排版都和老师课件一致（以前这里是纯文字 HTML，所以看着不像 PPT）。
        -->
        <img
          v-if="slideImageUrl && !imageFailed"
          class="slide"
          :src="slideImageUrl"
          :alt="`第 ${currentPage} 页`"
          @error="imageFailed = true"
        />
        <!-- 图片渲染失败时退回纯文字版，至少不白屏 -->
        <iframe
          v-else-if="slideTextUrl"
          class="slide"
          :src="slideTextUrl"
          :title="`第 ${currentPage} 页（文字版）`"
        ></iframe>

        <p class="stage-hint">
          <template v-if="ended">画面停在老师下课时的那一页。</template>
          <template v-else>画面与老师翻页实时同步（视频直播是下一轮的事）。</template>
          <span v-if="lastError" class="warn">实时通道：{{ lastError }}</span>
        </p>
      </section>

      <aside class="qa">
        <h2 class="qa-title">AI 问答助手</h2>

        <div class="qa-list">
          <div v-for="r in records" :key="r.id" class="qa-item">
            <p class="q">
              <span v-if="r.pageNo" class="q-page">第 {{ r.pageNo }} 页</span>
              {{ r.question }}
            </p>
            <p class="a" :class="{ failed: r.status === 'FAILED' }">{{ r.answer }}</p>
          </div>

          <p v-if="recordsError" class="qa-off">{{ recordsError }}</p>
          <p v-else-if="!records.length" class="qa-off">还没有问答，问点什么吧</p>
        </div>

        <!--
          本页的预置思考题（AI 解析时生成）。点一下填进输入框，不直接发出去——
          既省得学生自己组织语言，也顺手告诉他「这一页准备了哪几个方向」。
        -->
        <div v-if="currentPresets.length" class="presets">
          <p class="presets-title">本页思考题</p>
          <button
            v-for="(q, i) in currentPresets"
            :key="i"
            class="preset"
            :disabled="!canAsk"
            @click="usePreset(q)"
          >
            {{ q }}
          </button>
        </div>

        <!-- SKIPPED 提示：没调模型、也没落库，所以只在这里显示，不进上面的列表 -->
        <p v-if="skippedNotice" class="qa-notice">{{ skippedNotice }}</p>
        <p v-if="askError" class="ask-error" role="alert">{{ askError }}</p>

        <div class="qa-input">
          <input
            v-model="question"
            :disabled="!canAsk"
            :placeholder="canAsk ? `就第 ${currentPage} 页提问…` : '当前页不可提问'"
            @keyup.enter="ask"
          />
          <button :disabled="!canAsk || !question.trim()" @click="ask">
            {{ sending ? '回答中…' : cooldownLeft ? `${cooldownLeft} 秒后可问` : '发送' }}
          </button>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.live {
  max-width: 1120px;
  margin: 0 auto;
}

.head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.title {
  font-size: 22px;
  color: #333;
}

.tags {
  display: flex;
  gap: 8px;
}

.tag {
  font-size: 12px;
  padding: 4px 12px;
  border-radius: 20px;
  white-space: nowrap;
}

.tag.page {
  color: #d97757;
  background: #fff3e6;
}

.tag.on {
  color: #27ae60;
  background: #eafaf1;
}

.tag.off {
  color: #e67e22;
  background: #fff7e6;
}

.body {
  display: flex;
  gap: 18px;
}

.stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.slide {
  width: 100%;
  /* PPT 是 16:9。以前写死 480px 高度会把它压变形 */
  aspect-ratio: 16 / 9;
  object-fit: contain;
  border: 1px solid #eee;
  border-radius: 10px;
  background: #fff;
  display: block;
}

.ended-tag {
  color: #8a6d3b;
  background: #fcf8e3;
}

.ended-banner {
  font-size: 13px;
  color: #8a6d3b;
  background: #fcf8e3;
  border: 1px solid #faebcc;
  padding: 10px 14px;
  border-radius: 8px;
}

.stage-hint {
  font-size: 12px;
  color: #999;
}

.warn {
  color: #e67e22;
}

.qa {
  width: 360px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
}

.qa-title {
  font-size: 15px;
  padding: 14px 18px;
  border-bottom: 1px solid #eee;
  color: #333;
}

.qa-list {
  flex: 1;
  padding: 16px 18px;
  overflow-y: auto;
  max-height: 380px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.q {
  font-size: 13px;
  color: #333;
  margin-bottom: 5px;
}

.q-page {
  display: inline-block;
  font-size: 11px;
  color: #d97757;
  background: #fff3e6;
  border-radius: 4px;
  padding: 1px 6px;
  margin-right: 5px;
}

.a {
  font-size: 13px;
  color: #666;
  background: #f7f7f8;
  border-radius: 8px;
  padding: 9px 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.a.failed {
  color: #c0392b;
  background: #fdf0ee;
}

.qa-off {
  font-size: 13px;
  color: #aaa;
  text-align: center;
  padding: 30px 0;
  line-height: 1.8;
}

/* ── 本页预置思考题 ──────────────────────────────────────────── */
.presets {
  border-top: 1px solid #eee;
  padding: 12px 18px 4px;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.presets-title {
  font-size: 12px;
  color: #d97757;
  margin-bottom: 2px;
}

.preset {
  text-align: left;
  font-size: 12px;
  color: #666;
  background: #fafafa;
  border: 1px solid #eee;
  border-radius: 14px;
  padding: 6px 12px;
  cursor: pointer;
  line-height: 1.6;
}

.preset:hover:not(:disabled) {
  border-color: #f0c8b8;
  color: #d97757;
  background: #fff8f4;
}

.preset:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* SKIPPED 提示。用中性色而不是红色：它不是错误，只是「这页没内容」 */
.qa-notice {
  font-size: 13px;
  color: #8a6d3b;
  background: #fcf8e3;
  padding: 9px 12px;
  margin: 8px 18px 0;
  border-radius: 8px;
  line-height: 1.7;
}

.ask-error {
  font-size: 13px;
  color: #c0392b;
  background: #fdf0ee;
  padding: 9px 12px;
  margin: 0 18px;
  border-radius: 8px;
}

.qa-input {
  display: flex;
  gap: 8px;
  padding: 14px 18px;
  border-top: 1px solid #eee;
}

.qa-input input {
  flex: 1;
  min-width: 0;
  padding: 10px 13px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
}

.qa-input input:focus {
  outline: none;
  border-color: #d97757;
}

.qa-input input:disabled {
  background: #f7f7f8;
  cursor: not-allowed;
}

.qa-input button {
  padding: 10px 18px;
  border: none;
  border-radius: 8px;
  background: #d97757;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
  white-space: nowrap;
}

.qa-input button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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

@media (max-width: 900px) {
  .body {
    flex-direction: column;
  }
  .qa {
    width: 100%;
  }
}
</style>
