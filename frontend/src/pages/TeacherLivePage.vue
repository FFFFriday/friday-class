<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { useAiParse } from '@/composables/useAiParse'
import { useClassChat } from '@/composables/useClassChat'
import { useClassSocket } from '@/composables/useClassSocket'
import { useScreenShare } from '@/composables/useScreenShare'
import ClassChatPanel from '@/components/features/ClassChatPanel.vue'
import { FcTag } from '@/components/base'

const route = useRoute()

const session = ref(null)
const pages = ref([])
const currentPage = ref(1)
const loading = ref(true)
const jumping = ref(false)
/** 翻页失败只提示、不清空页面——直播画面不该因为一次请求失败就消失。 */
const pageError = ref('')

/**
 * AI 解析状态（F002）。
 *
 * 老师在这里看到的理由：课件没解析过时，学生提问是**静默失败**的——
 * 学生那边只会看到「本页暂无解析内容」，老师在讲台上完全不知道。
 * 所以这件事必须在开课时就摆在老师面前，并给一个一键处理的入口。
 */
const {
  loaded: parseLoaded,
  triggering: parseTriggering,
  error: parseError,
  status: parseStatus,
  running: parseRunning,
  percent: parsePercent,
  statusLabel: parseStatusLabel,
  hint: parseHint,
  refresh: refreshParse,
  trigger: triggerParse,
} = useAiParse(() => session.value?.coursewareId)

const totalPages = computed(() => pages.value.length)

/**
 * 当前页对应的 pageId。
 * 讨论区发言要带上它，课堂记录才能显示「这条是在第 N 页说的」。
 * 找不到就返回 null——后端接受 pageId 为空，发言不会被它挡住。
 */
const currentPageId = computed(() => {
  const found = pages.value.find((p) => p.pageNo === currentPage.value)
  return found ? found.id : null
})

const statusText = computed(
  () => ({ NOT_STARTED: '未开始', LIVE: '直播中', ENDED: '已结束' })[session.value?.status] || '',
)

// 老师自己也订阅广播：这样「投影机 + 笔记本」两个窗口能自动同步页码。
// 翻页本身走 POST，不靠 WebSocket——WS 一断老师就翻不了页，而 POST 只要网络通就行。
const socket = useClassSocket(route.params.sessionId)
const { connected, lastError, ended: broadcastEnded, paused, selfId, selfRole } = socket

const offPage = socket.on('page', (msg) => {
  if (msg.pageNo !== currentPage.value) {
    currentPage.value = msg.pageNo
  }
})

// ── 屏幕共享（M1） ─────────────────────────────────────────
const {
  sharing,
  starting: shareStarting,
  stopping: shareStopping,
  error: shareError,
  micWarning,
  participants,
  peerStates,
  connectedCount,
  start: startShare,
  stop: stopShare,
} = useScreenShare(socket, route.params.sessionId)

/** 某个学生的连接状态文案。老师据此判断「他到底看没看到画面」。 */
function peerStateLabel(userId) {
  return (
    {
      new: '待协商',
      connecting: '连接中',
      connected: '已连接',
      disconnected: '已断开',
      failed: '连接失败',
      closed: '已关闭',
    }[peerStates.value[userId]] || '未连接'
  )
}

function peerStateClass(userId) {
  const state = peerStates.value[userId]
  if (state === 'connected') return 'ok'
  if (state === 'failed' || state === 'disconnected') return 'bad'
  return 'pending'
}

// ── 讨论区（M2） ───────────────────────────────────────────
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

/** 幻灯片图片加载失败时回退到纯文字 HTML 版（见模板里的 @error 分支）。 */
const imageFailed = ref(false)

/** 下课中 / 下课失败提示。 */
const ending = ref(false)
const endError = ref('')

/** 已结束 = 接口查出来是 ENDED，或刚收到下课广播。两者任一成立。 */
const ended = computed(() => broadcastEnded.value || session.value?.status === 'ENDED')

/**
 * 课堂上真正会「卡住」AI 助手的两种情况：从没解析过、上次解析失败。
 *
 * ⚠️ 故意**不含 PARTIAL**：部分页失败时绝大多数页是好的，课上反复提示只会让老师分心，
 * 而且他此刻也没法处理。那种情况在课件详情页看得到——那里才是验收解析质量的地方。
 */
const parseBlocking = computed(() => parseStatus.value === null || parseStatus.value === 'FAILED')

/**
 * 在课堂上触发解析。比课件详情页多一道二次确认，原因见下。
 *
 * 解析一开始，课件状态就变成 PARSING，而 QaService 在 PARSING 状态下
 * 对**所有页**都返回「课件还在解析中」——注意是**不调模型**的那种直接跳过。
 * 也就是说老师一点下去，全班学生的 AI 助手立刻停摆，69 页的课件大约要 100 秒。
 *
 * 这不是一个能随手点的按钮，所以和「下课」一样要二次确认。
 */
async function startParse() {
  const ok = window.confirm(
    '开始解析后，课件会进入「解析中」状态，全班学生的 AI 助手都会暂时收到「课件还在解析中」，直到解析结束。\n\n确定现在解析吗？',
  )
  if (!ok) return
  await triggerParse()
}

// 换页要先清掉上一页的回退标记，否则某一页渲染失败会让后面所有页都退化成文字版
watch(currentPage, () => {
  imageFailed.value = false
})

async function load() {
  loading.value = true
  pageError.value = ''
  try {
    const data = await http.get(`/session/${route.params.sessionId}`)
    session.value = data
    currentPage.value = data.currentPage ?? 1

    const pageList = await http.get(`/courseware/${data.coursewareId}/pages`)
    pages.value = pageList.list

    // 拿到 coursewareId 才能查解析状态。不 await：它失败不该挡住直播控制台
    refreshParse()

    // 讨论区历史同样不 await：拉不到也不该挡住翻页与共享
    loadChat()
  } catch (e) {
    pageError.value = e.message || '加载失败'
    session.value = null
  } finally {
    loading.value = false
  }
}

async function goTo(pageNo) {
  if (!session.value || jumping.value || ended.value) return
  if (pageNo < 1) return
  if (totalPages.value > 0 && pageNo > totalPages.value) return

  jumping.value = true
  pageError.value = ''
  try {
    // 服务端会「先落库、再广播」，所以这里拿到响应时，学生的页面已经在切了
    const data = await http.post(`/session/${route.params.sessionId}/page`, { pageNo })
    session.value = data
    currentPage.value = data.currentPage ?? pageNo
  } catch (e) {
    pageError.value = e.message || '翻页失败'
  } finally {
    jumping.value = false
  }
}

/** 下课。学生端会立刻收到广播并显示「已结束」。 */
async function endClass() {
  if (ending.value || ended.value) return

  // 下课不可撤销：学生端会马上看到「已结束」，老师也翻不了页了。
  // 所以必须二次确认，避免手滑把一节正在上的课结掉。
  if (!window.confirm('确定要下课吗？学生端会立即显示「本节课已结束」，之后无法再翻页。')) {
    return
  }

  ending.value = true
  endError.value = ''
  try {
    const data = await http.post(`/session/${route.params.sessionId}/end`)
    session.value = data
  } catch (e) {
    endError.value = e.message || '下课失败'
  } finally {
    ending.value = false
  }
}

function onKeydown(event) {
  // 已下课就别再拦方向键了，否则老师在已结束的页面上按空格还是会被吃掉
  if (ended.value) return

  // 忽略在输入框里按的键，否则老师想打字都打不了
  const tag = (event.target?.tagName || '').toLowerCase()
  if (tag === 'input' || tag === 'select' || tag === 'textarea') return

  if (event.key === 'ArrowRight' || event.key === 'PageDown' || event.key === ' ') {
    event.preventDefault()
    goTo(currentPage.value + 1)
  } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
    event.preventDefault()
    goTo(currentPage.value - 1)
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  offPage()
})

watch(() => route.params.sessionId, load, { immediate: true })
</script>

<template>
  <div class="teach">
    <header class="bar">
      <div class="meta">
        <h1 class="title">{{ session?.title || '直播课堂' }}</h1>
        <p class="sub">
          <span>{{ session?.coursewareName }}</span>
          <template v-if="statusText">
            <span class="sep">·</span>
            <span class="status" :class="`status-${(session?.status || '').toLowerCase()}`">
              {{ statusText }}
            </span>
          </template>
          <span class="sep">·</span>
          <span class="ws" :class="connected ? 'ws-on' : 'ws-off'">
            {{ connected ? '实时通道已连接' : '实时通道断开，重连中…' }}
          </span>
        </p>
      </div>

      <div class="controls">
        <button class="btn" :disabled="jumping || currentPage <= 1" @click="goTo(currentPage - 1)">
          ← 上一页
        </button>
        <span class="page-no">{{ currentPage }} / {{ totalPages || '?' }}</span>
        <button
          class="btn"
          :disabled="jumping || (totalPages > 0 && currentPage >= totalPages)"
          @click="goTo(currentPage + 1)"
        >
          下一页 →
        </button>
        <select
          v-if="totalPages"
          class="jump"
          :value="currentPage"
          @change="goTo(Number($event.target.value))"
        >
          <option v-for="p in pages" :key="p.id" :value="p.pageNo">第 {{ p.pageNo }} 页</option>
        </select>

        <!--
          屏幕共享。老师共享的应该是**演示页那个标签页**（/present/:id），
          不是这个控制台——否则学生连「上一页/下课」这些按钮一起看到。
        -->
        <button
          v-if="!ended"
          class="btn"
          :class="{ 'btn-share-on': sharing }"
          :disabled="shareStarting || shareStopping"
          @click="sharing ? stopShare() : startShare()"
        >
          {{ shareStarting ? '启动中…' : shareStopping ? '停止中…' : sharing ? '停止共享屏幕' : '开始共享屏幕' }}
        </button>
        <a
          v-if="!ended"
          class="btn btn-link"
          :href="`/present/${route.params.sessionId}`"
          target="_blank"
          rel="noopener"
        >
          打开演示页 ↗
        </a>

        <!-- 课堂记录：随时可看，不限于下课后——老师课上想回看某条发言也用得上 -->
        <router-link class="btn btn-link" :to="`/record/${route.params.sessionId}`">
          课堂记录
        </router-link>

        <!-- 下课。已经下课就不再显示，避免老师以为还能再结一次 -->
        <button v-if="!ended" class="btn btn-end" :disabled="ending" @click="endClass">
          {{ ending ? '正在下课…' : '下课' }}
        </button>
      </div>
    </header>

    <!-- 提示放在 banner 里，不遮挡下面的幻灯片 -->
    <p v-if="pageError" class="msg msg-error" role="alert">{{ pageError }}</p>
    <p v-else-if="endError" class="msg msg-error" role="alert">{{ endError }}</p>
    <p v-else-if="shareError" class="msg msg-error" role="alert">{{ shareError }}</p>
    <p v-else-if="lastError" class="msg msg-warn" role="status">{{ lastError }}</p>

    <!-- 麦克风没拿到时明说：否则老师会以为「学生怎么听不见我说话」 -->
    <p v-if="micWarning" class="msg msg-warn" role="status">{{ micWarning }}</p>

    <p v-if="paused" class="msg msg-warn" role="status">
      本课堂已被管理员暂停：学生端已收到遮罩，讨论与提问已禁用。
      画面是点对点直连的，服务端无法强制切断——如需真正停画面，请自行点「停止共享屏幕」。
    </p>

    <!--
      AI 解析出问题（或正在跑）时才摆到老师面前。
      解析顺利、以及「只有几页失败」的课上都不出现——课堂上不需要那块信息，
      老师此刻也没法处理，反复提示只会分心。
    -->
    <section
      v-if="parseLoaded && (parseBlocking || parseRunning)"
      class="ai-banner"
      :class="{ warn: parseBlocking }"
    >
      <div class="ai-row">
        <span class="ai-label">AI 解析</span>
        <span class="ai-state">{{ parseStatusLabel }}</span>
        <span class="ai-hint">{{ parseHint }}</span>
        <button
          v-if="parseBlocking"
          class="btn ai-btn"
          :disabled="parseTriggering"
          @click="startParse"
        >
          {{ parseTriggering ? '提交中…' : parseStatus === null ? '立即解析' : '重新解析' }}
        </button>
      </div>

      <div
        v-if="parseRunning"
        class="ai-bar"
        role="progressbar"
        :aria-valuenow="parsePercent"
        aria-valuemin="0"
        aria-valuemax="100"
      >
        <div class="ai-bar-fill" :style="{ width: parsePercent + '%' }"></div>
      </div>

      <p v-if="parseError" class="ai-error" role="alert">{{ parseError }}</p>
    </section>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!session" class="empty error-text">课堂不存在或加载失败</div>

    <template v-else>
      <!-- 下课了就明说，否则老师会以为是自己卡了 -->
      <p v-if="ended" class="ended-banner" role="status">
        本节课已结束，学生端已收到通知。如需继续，请回到课件页重新开课。
      </p>

      <!--
        直接 <img> 引用后端渲染好的整页 PPT 图片（GET /slides/{id}/page{n}.png）。
        以前这里是 iframe + 纯文字 HTML —— 那份 HTML 只是把文字倒进白底 div，
        根本不是课件的样子，所以老师看到的是「一片文字而不是 PPT」。
      -->
      <img
        v-if="!imageFailed"
        class="slide-img"
        :src="`/slides/${session.coursewareId}/page${currentPage}.png`"
        :alt="`第 ${currentPage} 页`"
        @error="imageFailed = true"
      />
      <!-- 图片渲染失败（畸形页 / 服务器缺字体）时退回纯文字版，至少不白屏 -->
      <iframe
        v-else
        class="slide-frame"
        :src="`/slides/${session.coursewareId}/page${currentPage}.html`"
        title="课件幻灯片（文字版）"
      ></iframe>

      <p class="hint">
        <template v-if="ended">键盘 ← → 与翻页按钮已停用。</template>
        <template v-else>
          键盘 ← → 也能翻页。学生端会实时跟着切页，AI 回答也按当前页给。
          <template v-if="sharing">
            共享中：学生画面来自你共享的那个标签页（建议共享「打开演示页」开出来的那个）。
          </template>
        </template>
      </p>

      <!-- 在线名单 + 讨论区 -->
      <div class="lower">
        <section class="online">
          <h2 class="lower__title">
            在线
            <span class="online__count">{{ participants.length }} 人</span>
            <span v-if="sharing" class="online__stream">已连接 {{ connectedCount }}</span>
          </h2>

          <ul class="online__list">
            <li v-for="p in participants" :key="p.userId" class="online__item">
              <span class="online__name">{{ p.nickname || '匿名' }}</span>
              <FcTag :type="p.role === 'TEACHER' ? 'primary' : 'default'" size="sm">
                {{ p.role === 'TEACHER' ? '老师' : '学生' }}
              </FcTag>
              <!--
                只有共享中才显示每个学生的连接状态：
                没共享的时候所有人都是「未连接」，摆出来只会误导。
              -->
              <span
                v-if="sharing && p.role === 'STUDENT'"
                class="online__state"
                :class="`online__state--${peerStateClass(p.userId)}`"
              >
                {{ peerStateLabel(p.userId) }}
              </span>
            </li>

            <li v-if="!participants.length" class="online__empty">暂时没有其他人在线</li>
          </ul>
        </section>

        <section class="chat-wrap">
          <ClassChatPanel
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
            :can-delete="true"
            @send="(text) => sendChat(text, currentPageId ?? null)"
            @delete="removeChat"
            @load-older="loadOlder"
          />
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.teach {
  max-width: 1100px;
  margin: 0 auto;
}
.bar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.title {
  font-size: 20px;
  margin-bottom: 6px;
}
.sub {
  font-size: 13px;
  color: #999;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.sep {
  color: #ddd;
}
.status {
  color: #d97757;
}
.ws-on {
  color: #27ae60;
}
.ws-off {
  color: #e67e22;
}
.controls {
  display: flex;
  align-items: center;
  gap: 10px;
}
.btn {
  padding: 9px 16px;
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  background: #fff;
  color: #333;
  cursor: pointer;
  font-size: 14px;
}
.btn:hover:not(:disabled) {
  border-color: #d97757;
  color: #d97757;
}
.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 共享中：主色填充，让老师一眼看出「现在正在往外推画面」 */
.btn-share-on {
  background: var(--fc-primary);
  border-color: var(--fc-primary);
  color: var(--fc-text-invert);
}
.btn-share-on:hover:not(:disabled) {
  background: var(--fc-primary-hover);
  border-color: var(--fc-primary-hover);
  color: var(--fc-text-invert);
}

/* 链接样式的按钮（打开演示页）。用 <a> 而不是 <button>：
   它要能按住 Ctrl 新开标签页、能右键复制链接。 */
.btn-link {
  display: inline-flex;
  align-items: center;
  text-decoration: none;
  color: var(--fc-primary);
  border-color: var(--fc-primary-border);
}
.btn-link:hover {
  background: var(--fc-primary-bg);
  color: var(--fc-primary-hover);
}

/* ── 在线名单 + 讨论区 ───────────────────────────────────── */
.lower {
  display: flex;
  gap: 16px;
  margin-top: 20px;
  align-items: flex-start;
}

.online {
  width: 260px;
  flex-shrink: 0;
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  padding: 14px 16px;
}

.lower__title {
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text);
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  margin-bottom: 10px;
}

.online__count {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  font-weight: 400;
}

.online__stream {
  margin-left: auto;
  font-size: var(--fc-font-xs);
  color: var(--fc-success);
  font-weight: 400;
}

.online__list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.online__item {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-sm);
}

.online__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--fc-text);
}

.online__state {
  font-size: var(--fc-font-xs);
}
.online__state--ok {
  color: var(--fc-success);
}
.online__state--bad {
  color: var(--fc-danger);
}
.online__state--pending {
  color: var(--fc-text-faint);
}

.online__empty {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.chat-wrap {
  flex: 1;
  min-width: 0;
  height: 420px;
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  overflow: hidden;
}

@media (max-width: 900px) {
  .lower {
    flex-direction: column;
  }
  .online {
    width: 100%;
  }
  .chat-wrap {
    width: 100%;
  }
}

/* 下课是破坏性操作：用描边红，与橙色主色区分开，避免和「下一页」看成一类 */
.btn-end {
  margin-left: 6px;
  color: #c0392b;
  border-color: #f3ddd4;
}

.btn-end:hover:not(:disabled) {
  border-color: #e74c3c;
  color: #e74c3c;
}

/* ── AI 解析提醒 ─────────────────────────────────────────────── */
.ai-banner {
  background: #fff;
  border: 1px solid #eee;
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 12px;
}

/* 要老师动手时才上暖色边；正在解析时保持中性，不刺眼 */
.ai-banner.warn {
  border-color: #f0d9b0;
  background: #fffdf7;
}

.ai-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.ai-label {
  font-size: 13px;
  font-weight: 600;
  color: #333;
}

.ai-state {
  font-size: 12px;
  padding: 3px 10px;
  border-radius: 20px;
  color: #d97757;
  background: #fff3e6;
}

.ai-hint {
  flex: 1;
  min-width: 200px;
  font-size: 12px;
  color: #999;
  line-height: 1.6;
}

/* 必须排在 .btn 之后：同为单类选择器，靠顺序覆盖内边距 */
.ai-btn {
  padding: 6px 14px;
  font-size: 13px;
}

.ai-bar {
  margin-top: 10px;
  height: 5px;
  border-radius: 3px;
  background: #f0f0f0;
  overflow: hidden;
}

.ai-bar-fill {
  height: 100%;
  background: #d97757;
  /* 进度是每页跳一次的，加过渡让它看起来是「在走」而不是「在跳」 */
  transition: width 0.4s ease;
}

.ai-error {
  margin-top: 8px;
  font-size: 12px;
  color: #c0392b;
}

.ended-banner {
  font-size: 13px;
  color: #8a6d3b;
  background: #fcf8e3;
  border: 1px solid #faebcc;
  padding: 10px 14px;
  border-radius: 8px;
  margin-bottom: 12px;
}

/* PPT 是 16:9。用 aspect-ratio 而不是写死高度：窄屏时会等比缩小而不是拉伸变形 */
.slide-img {
  width: 100%;
  aspect-ratio: 16 / 9;
  object-fit: contain;
  border: 1px solid #eee;
  border-radius: 8px;
  background: #fff;
  display: block;
}
.page-no {
  font-size: 14px;
  color: #666;
  min-width: 64px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}
.jump {
  padding: 8px 10px;
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  font-size: 13px;
  color: #666;
  background: #fff;
}
.msg {
  font-size: 13px;
  margin-bottom: 12px;
  padding: 9px 12px;
  border-radius: 6px;
}
.msg-error {
  color: #c0392b;
  background: #fdf0ee;
}
.msg-warn {
  color: #a06000;
  background: #fff7e6;
}
.slide-frame {
  width: 100%;
  height: 70vh;
  min-height: 420px;
  border: 1px solid #eee;
  border-radius: 8px;
  background: #fff;
}
.hint {
  margin-top: 10px;
  color: #999;
  font-size: 12px;
}
.empty {
  color: #999;
  text-align: center;
  padding: 60px 0;
}
.error-text {
  color: #e74c3c;
}
</style>
