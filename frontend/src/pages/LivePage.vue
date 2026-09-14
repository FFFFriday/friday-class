<script setup>
// 学生端课堂页：左边是「与老师同步的幻灯片」，右边是 AI 问答。
//
// 翻页同步走 WebSocket（见 composables/usePageSync）：
// HTTP 是「客户端问、服务端答」，服务端没有嘴，没法主动告诉学生「老师翻页了」。
// 所以需要一条服务端能主动往下推的长连接。
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { usePageSync } from '@/composables/usePageSync'

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

/** F004（学生问答智能体）尚未实现，相关接口返回 404。用它标记「问答区暂不可用」。 */
const qaUnavailable = ref(false)

const { currentPage: broadcastPage, connected, lastError } = usePageSync(
  route.params.sessionId,
  () => {
    askError.value = ''
  },
)

/** 广播优先；还没收到广播时用接口里的 currentPage 兜底；都没有则是第 1 页。 */
const currentPage = computed(() => broadcastPage.value ?? session.value?.currentPage ?? 1)

const slideUrl = computed(() => {
  const id = session.value?.coursewareId
  return id ? `/slides/${id}/page${currentPage.value}.html` : null
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

const canAsk = computed(
  () => !qaUnavailable.value && currentPageId.value !== null && !sending.value,
)

async function load() {
  loading.value = true
  loadError.value = ''
  askError.value = ''
  qaUnavailable.value = false
  try {
    const sessionId = route.params.sessionId
    session.value = await http.get(`/session/${sessionId}`)

    const pageList = await http.get(`/courseware/${session.value.coursewareId}/pages`)
    pages.value = pageList.list || []

    try {
      const recordList = await http.get('/qa/records', { params: { sessionId } })
      records.value = recordList.list || []
    } catch {
      // F004 还没实现，/qa/records 会返回 404「接口不存在」。
      // 这里**不能**让它把整个课堂页搞挂——翻页同步是好的，照常可用。
      qaUnavailable.value = true
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

  sending.value = true
  askError.value = ''
  try {
    const data = await http.post('/qa/ask', {
      sessionId: Number(route.params.sessionId),
      pageId: currentPageId.value,
      question: text,
    })
    records.value.push({ ...data, pageNo: currentPage.value })
    question.value = ''
  } catch (e) {
    askError.value = e.message || '发送失败'
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
        <span class="tag" :class="connected ? 'on' : 'off'">
          {{ connected ? '实时同步中' : '重连中…' }}
        </span>
      </div>
    </header>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="loadError" class="hint error-text">{{ loadError }}</div>

    <div v-else class="body">
      <section class="stage">
        <!--
          学生看的是「和老师同步的那一页幻灯片」。
          ⚠️ 幻灯片不存在时后端返回的是 HTTP 200 + 一行 JSON，iframe 里会显示 JSON 文字；
          正常路径下页码始终来自 pages 列表，不会取到不存在的页。
        -->
        <iframe v-if="slideUrl" class="slide" :src="slideUrl" :title="`第 ${currentPage} 页`"></iframe>
        <p class="stage-hint">
          画面与老师翻页实时同步（视频直播是下一轮的事）。
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

          <p v-if="qaUnavailable" class="qa-off">
            AI 问答还没上线（F004 开发中）。<br />
            现在可以先看左边跟着老师同步的幻灯片。
          </p>
          <p v-else-if="!records.length" class="qa-off">还没有问答，问点什么吧</p>
        </div>

        <p v-if="askError" class="ask-error" role="alert">{{ askError }}</p>

        <div class="qa-input">
          <input
            v-model="question"
            :disabled="!canAsk"
            :placeholder="
              qaUnavailable ? '问答功能开发中' : canAsk ? `就第 ${currentPage} 页提问…` : '当前页不可提问'
            "
            @keyup.enter="ask"
          />
          <button :disabled="!canAsk || !question.trim()" @click="ask">
            {{ sending ? '回答中…' : '发送' }}
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
  height: 480px;
  border: 1px solid #eee;
  border-radius: 10px;
  background: #fff;
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
