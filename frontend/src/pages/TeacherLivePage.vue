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
const { connected, lastError } = usePageSync(route.params.sessionId, (pageNo) => {
  if (pageNo !== currentPage.value) {
    currentPage.value = pageNo
  }
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
  if (!session.value || jumping.value) return
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

function onKeydown(event) {
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
      </div>
    </header>

    <!-- 提示放在 banner 里，不遮挡下面的幻灯片 -->
    <p v-if="pageError" class="msg msg-error" role="alert">{{ pageError }}</p>
    <p v-else-if="lastError" class="msg msg-warn" role="status">{{ lastError }}</p>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="!session" class="empty error-text">课堂不存在或加载失败</div>

    <template v-else>
      <iframe
        class="slide-frame"
        :src="`/slides/${session.coursewareId}/page${currentPage}.html`"
        title="课件幻灯片"
      ></iframe>
      <p class="hint">键盘 ← → 也能翻页。学生端会实时跟着切页，AI 回答也按当前页给。</p>
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
