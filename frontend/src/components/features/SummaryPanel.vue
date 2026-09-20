<script setup>
// 课堂总结面板（M5）。自己管状态、自己轮询——它是一个自成一体的功能块，
// 页面只需要给它一个 sessionId。
import { computed, onUnmounted, ref, watch } from 'vue'
import http from '@/api/http'
import { FcButton, FcEmptyState, FcLoading } from '@/components/base'

const props = defineProps({
  sessionId: { type: [Number, String], required: true },
})

/** 轮询间隔。一次总结几十秒，3 秒够密了，也不会把接口打爆。 */
const POLL_INTERVAL_MS = 3000

/**
 * 最多轮询多少轮。
 *
 * <p>与后端 {@code CallKind.SUMMARY} 的总预算（3 分钟）对齐——
 * 后端最多跑那么久，再轮下去一定是出了问题，不如停手并把话说清楚，
 * 而不是让老师对着一个转不完的圈。
 */
const MAX_POLLS = 70

const summary = ref(null)
const loading = ref(true)
const generating = ref(false)
const error = ref('')
let timer = null
let polls = 0

const status = computed(() => summary.value?.status || null)
const content = computed(() => summary.value?.content || '')
const running = computed(() => status.value === 'PENDING' || status.value === 'RUNNING')
const failed = computed(() => status.value === 'FAILED')

const statusText = computed(
  () =>
    ({
      PENDING: '排队中…',
      RUNNING: '正在生成…',
      SUCCESS: '已生成',
      FAILED: '生成失败',
    })[status.value] || '',
)

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function startPolling() {
  stopPolling()
  polls = 0
  timer = setInterval(async () => {
    polls += 1
    if (polls > MAX_POLLS) {
      stopPolling()
      error.value = '生成时间过长，请稍后刷新页面查看结果'
      return
    }
    await fetchSummary()
    if (!running.value) {
      stopPolling()
    }
  }, POLL_INTERVAL_MS)
}

async function fetchSummary() {
  try {
    summary.value = await http.get(`/session/${props.sessionId}/summary`)
  } catch (e) {
    error.value = e.message || '读取总结失败'
    stopPolling()
  } finally {
    loading.value = false
  }
}

async function generate() {
  generating.value = true
  error.value = ''
  try {
    summary.value = await http.post(`/session/${props.sessionId}/summary`)
    startPolling()
  } catch (e) {
    // 「本堂课没有任何记录」「正在生成中」都会走到这里，
    // 它们是**提示**不是故障，直接显示后端那句话即可。
    error.value = e.message || '触发生成失败'
  } finally {
    generating.value = false
  }
}

watch(
  () => props.sessionId,
  async () => {
    stopPolling()
    loading.value = true
    error.value = ''
    summary.value = null
    await fetchSummary()
    if (running.value) {
      startPolling()
    }
  },
  { immediate: true },
)

onUnmounted(stopPolling)
</script>

<template>
  <div class="summary">
    <FcLoading v-if="loading" text="读取总结…" />

    <template v-else>
      <div class="summary__bar">
        <div class="summary__state">
          <span v-if="statusText" class="summary__badge" :class="`summary__badge--${status?.toLowerCase()}`">
            {{ statusText }}
          </span>
          <span v-else class="summary__hint">还没有生成过总结</span>
          <span v-if="summary?.generatedAt" class="summary__time">
            {{ String(summary.generatedAt).replace('T', ' ').slice(0, 16) }}
          </span>
        </div>

        <FcButton
          size="sm"
          :variant="status === 'SUCCESS' ? 'secondary' : 'primary'"
          :loading="generating || running"
          :disabled="running"
          @click="generate"
        >
          {{ running ? '生成中…' : status === 'SUCCESS' ? '重新生成' : '生成总结' }}
        </FcButton>
      </div>

      <p v-if="error" class="summary__err" role="alert">{{ error }}</p>

      <!-- 失败要明说原因：只给一个红点的话，老师不知道该重试还是该找开发者 -->
      <p v-if="failed" class="summary__err" role="alert">
        生成失败：{{ summary?.errorMessage || '原因未知' }}
      </p>

      <div v-if="content" class="summary__content">
        <!-- 文本插值：总结由模型生成，同样不当作 HTML -->
        <p class="summary__text">{{ content }}</p>
      </div>

      <FcEmptyState
        v-else-if="!running && !failed && !error"
        size="sm"
        title="还没有总结"
        description="总结只基于本堂课的讨论与问答内容生成"
      />

      <p v-if="running" class="summary__hint summary__hint--running">
        正在整理本堂课的讨论与提问，大约需要几十秒…
      </p>
    </template>
  </div>
</template>

<style scoped>
.summary {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
}

.summary__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fc-space-3);
  flex-wrap: wrap;
}

.summary__state {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-sm);
  color: var(--fc-text-faint);
}

.summary__badge {
  padding: 2px 10px;
  border-radius: var(--fc-radius-pill);
  font-size: var(--fc-font-xs);
  background: var(--fc-bg-muted);
  color: var(--fc-text-muted);
}
.summary__badge--success {
  background: var(--fc-success-bg);
  color: var(--fc-success);
}
.summary__badge--running,
.summary__badge--pending {
  background: var(--fc-primary-bg);
  color: var(--fc-primary);
}
.summary__badge--failed {
  background: var(--fc-danger-bg);
  color: var(--fc-danger);
}

.summary__time {
  font-size: var(--fc-font-xs);
  font-variant-numeric: tabular-nums;
}

.summary__content {
  padding: var(--fc-space-4);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  background: var(--fc-bg);
}

.summary__text {
  margin: 0;
  font-size: var(--fc-font);
  line-height: 1.85;
  color: var(--fc-text);
  /* 保留换行：总结是按「1. 2. 3.」分行的 */
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.summary__hint {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-faint);
  margin: 0;
}

.summary__hint--running {
  color: var(--fc-primary);
}

.summary__err {
  font-size: var(--fc-font-sm);
  color: var(--fc-danger);
  margin: 0;
}
</style>
