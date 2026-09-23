<script setup>
// 学生端课堂页：左边是老师的实时画面，右边是「讨论区 / AI 问答」标签页。
//
// 【为什么右侧改成标签页】
// 原来是「幻灯片 + AI 面板」两块。新增讨论区之后，如果三块都堆在右栏，
// 中间的视频区会被压得很小。所以右栏改成标签切换，一次只占一块。
//
// 【为什么有「进入课堂」这道门】
// 带声音的 <video> 自动播放会被浏览器拦截，必须先有一次用户手势。
// 这不是体验优化，是硬性前提——没有它，学生看到的是黑屏。
//
// 【实时通道只有一条】
// 翻页、讨论区、在线名单、屏幕共享信令全部跑在同一条 WebSocket 上
// （见 composables/useClassSocket）。这里创建一次，传给其它 composable。

import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { useClassSocket } from '@/composables/useClassSocket'
import { useClassChat } from '@/composables/useClassChat'
import { useScreenViewer } from '@/composables/useScreenViewer'
import { usePromptPack } from '@/composables/usePromptPack'
import ClassChatPanel from '@/components/features/ClassChatPanel.vue'
import PageKnowledgePanel from '@/components/features/PageKnowledgePanel.vue'
import { FcButton } from '@/components/base'

const route = useRoute()

/**
 * 右侧三块。用标签页而不是三个面板堆叠——
 * 都摆出来的话中间的视频区会被压得很小，而视频才是学生要看的主内容。
 */
const TABS = [
  { key: 'chat', label: '讨论区' },
  { key: 'knowledge', label: '知识点' },
  { key: 'qa', label: 'AI 问答' },
]

const session = ref(null)
const pages = ref([])
const records = ref([])
const question = ref('')
const activeTab = ref('chat')

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
 * 把剩余时间写在按钮上，他一眼就知道该等。
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

/**
 * 「本页暂无解析内容」这类临时提示。
 *
 * 后端的 SKIPPED 响应**没有落库**（`id` 为 null）。把它追加进问答列表会有两个问题：
 * 刷新页面它就消失了（看起来像记录丢了），而它本来也不在数据库里。
 * 所以单独存一份，只当提示显示。
 */
const skippedNotice = ref('')

// ── 实时通道 ───────────────────────────────────────────────

const socket = useClassSocket(route.params.sessionId)

/** 翻页广播。WS 断线期间用接口里的 currentPage 兜底。 */
const broadcastPage = ref(null)

const offPage = socket.on('page', (msg) => {
  broadcastPage.value = msg.pageNo
  askError.value = ''
})

/** 被踢出课堂：提示并断开，别再让学生对着一个连不上的页面发愣。 */
const kickedReason = ref('')
const offKicked = socket.on('kicked', (msg) => {
  kickedReason.value = msg.reason || '你已被移出本课堂'
  socket.close()
})

const { connected, lastError, ended: broadcastEnded, paused, selfId, selfRole } = socket

// ── 讨论区 ────────────────────────────────────────────────

const {
  messages,
  loading: chatLoading,
  loadingMore: chatLoadingMore,
  hasMore: chatHasMore,
  loadError: chatLoadError,
  sendError: chatSendError,
  cooldownLeft: chatCooldown,
  load: loadChat,
  loadOlder,
  send: sendChat,
  remove: removeChat,
} = useClassChat(socket, route.params.sessionId)

// ── 屏幕观看 ──────────────────────────────────────────────

const {
  entered,
  stream: remoteStream,
  state: viewerState,
  error: viewerError,
  playBlocked,
  liveButNotEntered,
  hasVideo,
  enter,
  refresh: refreshStream,
  attach,
  playNow,
} = useScreenViewer(socket, route.params.sessionId)

const videoEl = ref(null)

// <video> 只在「已进入」分支里渲染，所以要用 watch 而不是 onMounted——
// 元素可能在 setup 之后才出现
watch(videoEl, (el) => {
  if (el) attach(el)
})

onMounted(() => {
  refreshStream()
  loadChat()
})

// ── 知识点包与页码推导 ────────────────────────────────────

/** 这份直播用的课件 ID。课堂加载完才有值。 */
const coursewareId = computed(() => session.value?.coursewareId ?? null)

/**
 * 提示词包：一次拉全所有页的知识点与预置提问，按 pageId 建索引。
 *
 * `watchParsing: true` 是必需的——学生常在老师刚点完解析就进课堂，
 * 那一刻包里还是空的。不盯着解析进度自动重拉的话，学生会一直看到
 * 「本页暂无解析内容」，而实际上解析十几秒后就跑完了。
 */
const {
  pagePack,
  load: loadPack,
  loading: packLoading,
  parseStatus: packParseStatus,
  parsed: packParsed,
} = usePromptPack(() => coursewareId.value, {
  watchParsing: true,
})

/** 当前页的提示词素材（知识点 + 预置提问）。拿不到时是 null。 */
const currentPack = computed(() => pagePack(currentPageId.value))

/**
 * 点「本页思考题」的 chip：填进提问框，**不自动发出**。
 *
 * 学生的意图往往是「在这基础上改一下再问」，直接发出去会白花一次模型调用。
 * 顺手切到「AI 问答」标签，让填好的输入框立刻可见——
 * 否则学生点完 chip 什么都没发生，会以为按钮坏了。
 */
function useQuestionFromKnowledge(text) {
  question.value = text
  activeTab.value = 'qa'
}

/** 广播优先；还没收到广播时用接口里的 currentPage 兜底；都没有则是第 1 页。 */
const currentPage = computed(() => broadcastPage.value ?? session.value?.currentPage ?? 1)

/** 已结束 = 接口查出来是 ENDED，或刚收到下课广播。 */
const ended = computed(() => broadcastEnded.value || session.value?.status === 'ENDED')

/** 整页 PPT 图片（老师没共享屏幕时的降级画面）。 */
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
    !ended.value &&
    !paused.value,
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
      recordsError.value = e.message || '问答记录暂时读不到'
    }
  } catch (e) {
    loadError.value = e.message || '加载失败'
    session.value = null
  } finally {
    loading.value = false
  }
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

/** 画面区域的说明文字。学生需要知道「现在到底在发生什么」。 */
const stageHint = computed(() => {
  if (kickedReason.value) return ''
  if (ended.value) return '本节课已结束，画面停在老师下课的那一刻。'
  if (!entered.value) return '点击「进入课堂」后即可看到老师的画面并听到声音。'
  if (hasVideo.value) return '正在接收老师的屏幕画面与麦克风声音。'
  if (liveButNotEntered.value) return '老师正在共享屏幕，正在建立连接…'
  if (viewerState.value === 'stopped') return '老师已停止共享屏幕。下方显示的是与老师同步的课件画面。'
  return '老师还没有开始共享屏幕，下方显示的是与老师同步的课件画面。'
})

onUnmounted(() => {
  offPage()
  offKicked()
  stopCooldown()
})

watch(() => route.params.sessionId, load, { immediate: true })
</script>

<template>
  <div class="live">
    <header class="head">
      <h1 class="title">{{ session?.title || '直播课堂' }}</h1>

      <div class="head__right">
        <!--
          页眉 folio —— 学生端最该看清的一个数字。

          因为它直接决定 AI 回答什么：本页的知识点、本页的预置提问，
          都是按这个数字取的。以前它和「直播中」「重连中」挤在同一排小胶囊里，
          大小一样、颜色一样，等于没说。
          分母带兜底：pages 还没拉回来时显示 ?，不让它闪成 0。
        -->
        <span v-if="session" class="folio">
          <span class="folio__no fc-num">{{ currentPage }}</span>
          <span class="folio__of fc-num">/ {{ pages.length || '?' }}</span>
        </span>

        <div class="tags">
          <span v-if="hasVideo" class="tag live-tag">直播中</span>
          <span v-if="ended" class="tag ended-tag">已结束</span>
          <span v-else class="tag" :class="connected ? 'on' : 'off'">
            {{ connected ? '实时同步中' : '重连中…' }}
          </span>
        </div>
      </div>
    </header>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="loadError" class="hint error-text">{{ loadError }}</div>

    <div v-else class="body">
      <section class="stage">
        <!-- 下课了要明说，否则学生盯着最后一页不知道是自己卡了还是课上完了 -->
        <p v-if="ended" class="ended-banner" role="status">本节课已结束，感谢参与。</p>
        <p v-else-if="paused" class="paused-banner" role="status">
          课堂已被管理员暂停，讨论与提问暂时不可用。
        </p>
        <p v-if="kickedReason" class="ended-banner" role="alert">{{ kickedReason }}</p>

        <!--
          <video> 始终渲染（用 v-show 而不是 v-if），这样 ref 一定拿得到元素，
          「有流了再绑」不用等组件重新挂载。
        -->
        <video
          v-show="hasVideo"
          ref="videoEl"
          class="stage__video"
          autoplay
          playsinline
        />

        <!-- 没有实时画面时，退回「与老师同步的课件图片」——这是答辩保险 -->
        <template v-if="!hasVideo">
          <img
            v-if="slideImageUrl && !imageFailed"
            class="slide"
            :src="slideImageUrl"
            :alt="`第 ${currentPage} 页`"
            @error="imageFailed = true"
          />
          <iframe
            v-else-if="slideTextUrl"
            class="slide"
            :src="slideTextUrl"
            :title="`第 ${currentPage} 页（文字版）`"
          ></iframe>
        </template>

        <!-- 自动播放被拦时的兜底：再点一下就出声 -->
        <button v-if="playBlocked" class="play-fix" type="button" @click="playNow">
          点击播放声音
        </button>

        <!--
          手势门。盖在画面上，点了才进入。
          这不只是「体验」——没有这次用户手势，带声音的播放一定会被浏览器拦掉。
        -->
        <div v-if="!entered && !ended" class="gate">
          <p class="gate__title">准备进入课堂</p>
          <p class="gate__desc">
            <template v-if="liveButNotEntered">老师正在共享屏幕</template>
            <template v-else>进入后可以看到老师的画面、听到声音，并参与课堂讨论</template>
          </p>
          <FcButton size="lg" @click="enter">进入课堂</FcButton>
          <p class="gate__hint">浏览器要求先点一下，才允许播放声音</p>
        </div>

        <p class="stage-hint">
          {{ stageHint }}
          <span v-if="lastError" class="warn">实时通道：{{ lastError }}</span>
          <span v-if="viewerError" class="warn">{{ viewerError }}</span>
        </p>
      </section>

      <aside class="side">
        <div class="side__tabs" role="tablist">
          <button
            v-for="tab in TABS"
            :key="tab.key"
            class="side__tab"
            :class="{ 'side__tab--on': activeTab === tab.key }"
            type="button"
            role="tab"
            :aria-selected="activeTab === tab.key"
            @click="activeTab = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>

        <div class="side__body">
          <ClassChatPanel
            v-if="activeTab === 'chat'"
            :messages="messages"
            :loading="chatLoading"
            :loading-more="chatLoadingMore"
            :has-more="chatHasMore"
            :load-error="chatLoadError"
            :send-error="chatSendError"
            :cooldown-left="chatCooldown"
            :self-id="selfId"
            :ended="ended"
            :paused="paused"
            :can-delete="selfRole === 'TEACHER' || selfRole === 'ADMIN'"
            @send="(text) => sendChat(text, currentPageId)"
            @delete="removeChat"
            @load-older="loadOlder"
          />

          <!--
            知识点面板（M3）。数据其实一直都有（usePromptPack 早就在拉），
            只是以前只喂给 AI、没渲染给学生看。页码联动是免费的：
            currentPageId 跟着翻页广播走，这里自然就换内容了。
          -->
          <PageKnowledgePanel
            v-else-if="activeTab === 'knowledge'"
            :pack="currentPack"
            :parse-status="packParseStatus"
            :parsed="packParsed"
            :loading="packLoading"
            :page-no="currentPage"
            @use-question="useQuestionFromKnowledge"
          />

          <div v-else class="qa">
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
              本页思考题搬到了「知识点」标签。那里能连知识点一起看，
              点一下会带着内容跳回本标签——同一份内容不必在两个标签里各显示一遍。
            -->
            <p v-if="currentPresets.length" class="qa-preset-hint">
              本页有 {{ currentPresets.length }} 个思考题，见「知识点」标签
            </p>

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
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.live {
  max-width: 1200px;
  margin: 0 auto;
}

/* 页眉下面压一条发丝线，把「页眉」和「正文」分开——文档的层次靠线，不靠阴影。 */
.head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 18px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--fc-border);
}

.title {
  font-size: var(--fc-font-xl);
  color: var(--fc-text);
}

/* margin-left:auto 把页码与状态推到右边。不用 justify-content: space-between——
   窄屏换行时 space-between 会让第一行只有一个元素、孤零零贴着左边。 */
.head__right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-left: auto;
}

/* ── 页眉 folio ─────────────────────────────────────────────── */
/* 当前页大、分母小。等宽 + tabular，翻页时宽度不跳。 */
.folio {
  display: inline-flex;
  align-items: baseline;
  gap: 3px;
}

.folio__no {
  font-size: var(--fc-font-xl);
  font-weight: var(--fc-weight-semibold);
  line-height: 1;
  color: var(--fc-text);
}

.folio__of {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.tags {
  display: flex;
  gap: 8px;
}

.tag {
  font-size: var(--fc-font-xs);
  padding: 4px 12px;
  border-radius: var(--fc-radius-pill);
  white-space: nowrap;
}

.tag.on {
  color: var(--fc-success);
  background: var(--fc-success-bg);
}

.tag.off {
  color: var(--fc-warning);
  background: var(--fc-warning-bg);
}

/* 「直播中」归朱色管——朱色的职责就是「正在发生」。
   原来是危险红，和「删除 / 下课」撞色，看久了以为课堂出事了。 */
.tag.live-tag {
  color: var(--fc-text-invert);
  background: var(--fc-accent);
}

.ended-tag {
  color: var(--fc-warning-text);
  background: var(--fc-warning-bg);
}

.body {
  display: flex;
  gap: 18px;
  align-items: flex-start;
}

.stage {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 视频与图片共用 16:9 的外框，切换时不会跳高度 */
.stage__video,
.slide {
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: contain;
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  background: #111;
  display: block;
}

.stage__video {
  /* 老师的屏幕不一定是 16:9，视频按容器等比缩放、两边留黑 */
  background: #000;
}

.slide {
  background: var(--fc-bg-panel);
}

.ended-banner,
.paused-banner {
  font-size: var(--fc-font-sm);
  color: var(--fc-warning-text);
  background: var(--fc-warning-bg);
  border: 1px solid var(--fc-warning-border);
  padding: 10px 14px;
  border-radius: var(--fc-radius);
}

/* ── 手势门 ───────────────────────────────────────────────── */
.gate {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--fc-space-3);
  text-align: center;
  padding: var(--fc-space-6);
  border-radius: var(--fc-radius);
  background: rgba(0, 0, 0, 0.72);
}

.gate__title {
  font-size: var(--fc-font-xl);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text-invert);
  margin: 0;
}

.gate__desc {
  font-size: var(--fc-font);
  color: rgba(255, 255, 255, 0.78);
  margin: 0;
}

.gate__hint {
  font-size: var(--fc-font-xs);
  color: rgba(255, 255, 255, 0.5);
  margin: 0;
}

.play-fix {
  position: absolute;
  left: 50%;
  bottom: 56px;
  transform: translateX(-50%);
  padding: 8px 20px;
  border-radius: var(--fc-radius-pill);
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  font-size: var(--fc-font-sm);
  box-shadow: var(--fc-shadow-lg);
}

.stage-hint {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.warn {
  color: var(--fc-warning);
  margin-left: var(--fc-space-2);
}

/* ── 右栏 ─────────────────────────────────────────────────── */
.side {
  width: 380px;
  flex-shrink: 0;
  /*
    必须有高度上限。不设的话，讨论区消息一多，整块面板会跟着长，
    把页面撑得比幻灯片高出一大截——右栏一长，左边画面就显得孤零零的。
    设了上限后，消息列表在面板内部自己滚。
  */
  max-height: 520px;
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.side__tabs {
  display: flex;
  border-bottom: 1px solid var(--fc-border);
}

.side__tab {
  flex: 1;
  padding: 12px 8px;
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
  border-bottom: 2px solid transparent;
  transition: color var(--fc-transition), border-color var(--fc-transition);
}

.side__tab:hover {
  color: var(--fc-primary);
}

.side__tab--on {
  color: var(--fc-primary);
  border-bottom-color: var(--fc-primary);
  font-weight: var(--fc-weight-semibold);
}

.side__body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/* ── AI 问答面板（沿用原有结构） ───────────────────────────── */
.qa {
  display: flex;
  flex-direction: column;
  /* 撑满 .side__body，让 .qa-list 成为唯一会滚动的区域 */
  flex: 1;
  min-height: 0;
}

.qa-list {
  flex: 1;
  min-height: 180px;
  padding: 16px 18px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.q {
  font-size: var(--fc-font-sm);
  color: var(--fc-text);
  margin-bottom: 5px;
}

.q-page {
  display: inline-block;
  font-size: 11px;
  color: var(--fc-primary);
  background: var(--fc-primary-bg);
  border-radius: var(--fc-radius-sm);
  padding: 1px 6px;
  margin-right: 5px;
}

.a {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
  background: var(--fc-bg);
  border-radius: var(--fc-radius);
  padding: 9px 12px;
  white-space: pre-wrap;
  word-break: break-word;
}

.a.failed {
  color: var(--fc-danger-dark);
  background: var(--fc-danger-bg);
}

.qa-off {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-faint);
  text-align: center;
  padding: 30px 0;
  line-height: 1.8;
}

.qa-preset-hint {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  padding: var(--fc-space-2) 18px 0;
  margin: 0;
}

/* SKIPPED 提示。用中性色而不是红色：它不是错误，只是「这页没内容」 */
.qa-notice {
  font-size: var(--fc-font-sm);
  color: var(--fc-warning-text);
  background: var(--fc-warning-bg);
  padding: 9px 12px;
  margin: 8px 18px 0;
  border-radius: var(--fc-radius);
  line-height: 1.7;
}

.ask-error {
  font-size: var(--fc-font-sm);
  color: var(--fc-danger-dark);
  background: var(--fc-danger-bg);
  padding: 9px 12px;
  margin: 0 18px;
  border-radius: var(--fc-radius);
}

.qa-input {
  display: flex;
  gap: 8px;
  padding: 14px 18px;
  border-top: 1px solid var(--fc-border);
}

.qa-input input {
  flex: 1;
  min-width: 0;
  padding: 10px 13px;
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font);
}

.qa-input input:focus {
  outline: none;
  border-color: var(--fc-primary);
}

.qa-input input:disabled {
  background: var(--fc-bg);
  cursor: not-allowed;
}

.qa-input button {
  padding: 10px 18px;
  border: none;
  border-radius: var(--fc-radius);
  background: var(--fc-primary);
  color: var(--fc-text-invert);
  cursor: pointer;
  font-size: var(--fc-font);
  white-space: nowrap;
}

.qa-input button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.hint {
  text-align: center;
  color: var(--fc-text-faint);
  padding: 60px 0;
  font-size: var(--fc-font);
}

.error-text {
  color: var(--fc-danger);
}

@media (max-width: 1000px) {
  .body {
    flex-direction: column;
  }
  .side {
    width: 100%;
  }
}
</style>
