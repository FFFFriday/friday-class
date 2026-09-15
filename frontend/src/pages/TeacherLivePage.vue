<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { usePageSync } from '@/composables/usePageSync'

const route = useRoute()

const session = ref(null)
const pages = ref([])
const currentPage = ref(1)
const loading = ref(true)
const jumping = ref(false)
/** 翻页失败只提示、不清空页面——直播画面不该因为一次请求失败就消失。 */
const pageError = ref('')

const totalPages = computed(() => pages.value.length)

const statusText = computed(
  () => ({ NOT_STARTED: '未开始', LIVE: '直播中', ENDED: '已结束' })[session.value?.status] || '',
)

// 老师自己也订阅广播：这样「投影机 + 笔记本」两个窗口能自动同步页码。
// 翻页本身走 POST，不靠 WebSocket——WS 一断老师就翻不了页，而 POST 只要网络通就行。
const { connected, lastError, ended: broadcastEnded } = usePageSync(route.params.sessionId, (pageNo) => {
  if (pageNo !== currentPage.value) {
    currentPage.value = pageNo
  }
})

/** 幻灯片图片加载失败时回退到纯文字 HTML 版（见模板里的 @error 分支）。 */
const imageFailed = ref(false)

/** 下课中 / 下课失败提示。 */
const ending = ref(false)
const endError = ref('')

/** 已结束 = 接口查出来是 ENDED，或刚收到下课广播。两者任一成立。 */
const ended = computed(() => broadcastEnded.value || session.value?.status === 'ENDED')

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
onUnmounted(() => window.removeEventListener('keydown', onKeydown))

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

        <!-- 下课。已经下课就不再显示，避免老师以为还能再结一次 -->
        <button v-if="!ended" class="btn btn-end" :disabled="ending" @click="endClass">
          {{ ending ? '正在下课…' : '下课' }}
        </button>
      </div>
    </header>

    <!-- 提示放在 banner 里，不遮挡下面的幻灯片 -->
    <p v-if="pageError" class="msg msg-error" role="alert">{{ pageError }}</p>
    <p v-else-if="endError" class="msg msg-error" role="alert">{{ endError }}</p>
    <p v-else-if="lastError" class="msg msg-warn" role="status">{{ lastError }}</p>

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
        <template v-else>键盘 ← → 也能翻页。学生端会实时跟着切页，AI 回答也按当前页给。</template>
      </p>
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
