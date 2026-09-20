<script setup>
// 课堂记录页（/record/:sessionId）—— 需求 3 + 需求 4。
//
// 三块内容：概览数字、发言时间线、分学生的 AI 问答，外加一块课后总结。
// 数据源全是现成的（讨论区发言、问答记录、出席记录），本页不新增存储。
//
// 权限：后端只放行本课堂的教师与管理员。学生看不到——记录里包含全班同学的
// 发言与提问，不是个人数据。

import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/api/http'
import ChatTimeline from '@/components/features/ChatTimeline.vue'
import StudentQaList from '@/components/features/StudentQaList.vue'
import SummaryPanel from '@/components/features/SummaryPanel.vue'
import { FcButton, FcLoading } from '@/components/base'

const route = useRoute()
const router = useRouter()

const TABS = [
  { key: 'chat', label: '发言记录' },
  { key: 'qa', label: 'AI 问答' },
  { key: 'summary', label: '课堂总结' },
]

const activeTab = ref('chat')

const overview = ref(null)
const loading = ref(true)
const error = ref('')

// ── 发言时间线 ─────────────────────────────────────────────
const chatMessages = ref([])
const chatLoading = ref(false)
const chatLoadingMore = ref(false)
const chatHasMore = ref(false)
const chatError = ref('')

// ── 分学生问答 ─────────────────────────────────────────────
const qaGroups = ref([])
const qaLoading = ref(false)
const qaError = ref('')

const sessionId = computed(() => route.params.sessionId)

const statusText = computed(
  () =>
    ({ NOT_STARTED: '未开始', LIVE: '进行中', PAUSED: '已暂停', ENDED: '已结束' })[
      overview.value?.status
    ] || '',
)

/** 时长展示成「1 小时 23 分」比「83 分钟」好读。 */
const durationText = computed(() => {
  const minutes = overview.value?.durationMinutes ?? 0
  if (minutes < 60) return `${minutes} 分钟`
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分`
})

async function loadOverview() {
  overview.value = await http.get(`/session/${sessionId.value}/record/overview`)
}

async function loadChat({ beforeId = null } = {}) {
  const first = !beforeId
  if (first) chatLoading.value = true
  else chatLoadingMore.value = true
  chatError.value = ''
  try {
    const params = { limit: 100 }
    if (beforeId) params.beforeId = beforeId
    const data = await http.get(`/session/${sessionId.value}/record/chat`, { params })
    const items = data.items || []

    if (first) {
      chatMessages.value = items
    } else {
      // 往前翻：按 id 去重后插到最前面
      const known = new Set(chatMessages.value.map((m) => m.id))
      chatMessages.value = [...items.filter((m) => !known.has(m.id)), ...chatMessages.value]
    }
    chatHasMore.value = data.hasMore
  } catch (e) {
    chatError.value = e.message || '发言记录加载失败'
  } finally {
    chatLoading.value = false
    chatLoadingMore.value = false
  }
}

function loadOlderChat() {
  if (!chatMessages.value.length || chatLoadingMore.value) return
  loadChat({ beforeId: chatMessages.value[0].id })
}

async function loadQa() {
  qaLoading.value = true
  qaError.value = ''
  try {
    const data = await http.get(`/session/${sessionId.value}/record/qa`)
    qaGroups.value = data.list || []
  } catch (e) {
    qaError.value = e.message || 'AI 问答记录加载失败'
  } finally {
    qaLoading.value = false
  }
}

async function load() {
  loading.value = true
  error.value = ''
  overview.value = null
  chatMessages.value = []
  qaGroups.value = []
  activeTab.value = 'chat'
  try {
    await loadOverview()
    // 两块明细都不 await：先让概览数字出来，内容慢慢填。
    // 记录页是为「回顾」服务的，不是实时页，没必要卡着白屏等。
    loadChat()
    loadQa()
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

watch(sessionId, load, { immediate: true })
</script>

<template>
  <div class="record">
    <header class="head">
      <div class="head__main">
        <h1 class="head__title">{{ overview?.title || '课堂记录' }}</h1>
        <p class="head__meta">
          <span v-if="overview?.coursewareName">{{ overview.coursewareName }}</span>
          <template v-if="statusText">
            <span class="head__sep">·</span>
            <span>{{ statusText }}</span>
          </template>
          <template v-if="overview?.startedAt">
            <span class="head__sep">·</span>
            <span>{{ String(overview.startedAt).replace('T', ' ').slice(0, 16) }}</span>
          </template>
        </p>
      </div>

      <FcButton variant="secondary" size="sm" @click="router.back()">返回</FcButton>
    </header>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="error" class="hint hint--error">{{ error }}</div>

    <template v-else>
      <!--
        四个数字。注意「提问人数」与「AI 问答」不是一回事：
        一个学生问 20 次是问答 20 条、提问人数 1 人。
        教学上关心的是后者（有多少人真的在用），所以两个都摆出来。
      -->
      <div class="stats">
        <div class="stat">
          <span class="stat__value">{{ durationText }}</span>
          <span class="stat__label">上课时长</span>
        </div>
        <div class="stat">
          <span class="stat__value">{{ overview?.participantCount ?? 0 }}</span>
          <span class="stat__label">参与人数</span>
        </div>
        <div class="stat">
          <span class="stat__value">{{ overview?.chatCount ?? 0 }}</span>
          <span class="stat__label">讨论发言</span>
        </div>
        <div class="stat">
          <span class="stat__value">{{ overview?.qaCount ?? 0 }}</span>
          <span class="stat__label">AI 问答</span>
        </div>
        <div class="stat">
          <span class="stat__value">{{ overview?.studentQaCount ?? 0 }}</span>
          <span class="stat__label">提问人数</span>
        </div>
      </div>

      <div class="tabs" role="tablist">
        <button
          v-for="tab in TABS"
          :key="tab.key"
          class="tab"
          :class="{ 'tab--on': activeTab === tab.key }"
          type="button"
          role="tab"
          :aria-selected="activeTab === tab.key"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </div>

      <section class="panel">
        <ChatTimeline
          v-if="activeTab === 'chat'"
          :messages="chatMessages"
          :loading="chatLoading"
          :loading-more="chatLoadingMore"
          :has-more="chatHasMore"
          :error="chatError"
          @load-older="loadOlderChat"
        />

        <StudentQaList
          v-else-if="activeTab === 'qa'"
          :groups="qaGroups"
          :loading="qaLoading"
          :error="qaError"
        />

        <SummaryPanel v-else :session-id="sessionId" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.record {
  max-width: 900px;
  margin: 0 auto;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--fc-space-4);
  margin-bottom: var(--fc-space-5);
}

.head__title {
  font-size: var(--fc-font-xl);
  color: var(--fc-text);
  margin-bottom: var(--fc-space-1);
}

.head__meta {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  flex-wrap: wrap;
  font-size: var(--fc-font-sm);
  color: var(--fc-text-faint);
  margin: 0;
}

.head__sep {
  color: var(--fc-border-strong);
}

.stats {
  display: flex;
  gap: var(--fc-space-3);
  flex-wrap: wrap;
  margin-bottom: var(--fc-space-5);
}

.stat {
  flex: 1;
  min-width: 110px;
  padding: var(--fc-space-3) var(--fc-space-4);
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat__value {
  font-size: var(--fc-font-lg);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-primary);
  font-variant-numeric: tabular-nums;
}

.stat__label {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.tabs {
  display: flex;
  gap: var(--fc-space-1);
  border-bottom: 1px solid var(--fc-border);
  margin-bottom: var(--fc-space-4);
}

.tab {
  padding: var(--fc-space-2) var(--fc-space-4);
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
  border-bottom: 2px solid transparent;
  transition: color var(--fc-transition), border-color var(--fc-transition);
}

.tab:hover {
  color: var(--fc-primary);
}

.tab--on {
  color: var(--fc-primary);
  border-bottom-color: var(--fc-primary);
  font-weight: var(--fc-weight-semibold);
}

.panel {
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  padding: var(--fc-space-4);
}

.hint {
  text-align: center;
  color: var(--fc-text-faint);
  padding: 60px 0;
  font-size: var(--fc-font);
}

.hint--error {
  color: var(--fc-danger);
}
</style>
