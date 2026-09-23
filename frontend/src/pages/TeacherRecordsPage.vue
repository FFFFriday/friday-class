<script setup>
// 课堂记录列表页（修改文档3）：教师自己上过的**全部**课堂，含已结束的。
//
// 数据源必须是 /session/taught，不能用 /session/mine ——
// 前者返回含已结束的全部，后者只返回未结束的，用它这个页面会永远是空的。
//
// 首页的「课堂记录」栏只展示最近 4 条，右上角「全部记录」跳到这。
import { computed, onMounted, ref } from 'vue'
import http from '@/api/http'
import { FcEmptyState, FcLoading } from '@/components/base'

const list = ref([])
const loading = ref(false)
const error = ref('')

/** 未结束的三种状态。它们排在已结束的前面。 */
const ACTIVE_STATUS = ['NOT_STARTED', 'LIVE', 'PAUSED']

const active = computed(() => list.value.filter((s) => ACTIVE_STATUS.includes(s.status)))
const ended = computed(() => list.value.filter((s) => s.status === 'ENDED'))

const STATUS_TEXT = { NOT_STARTED: '未开始', LIVE: '直播中', PAUSED: '已暂停', ENDED: '已结束' }

/**
 * 状态中文。表里没有就退回原始值，再没有就显示一个破折号。
 * 直接写 `STATUS_TEXT[s.status]` 的话，遇到没映射到的状态（后端的 status 由枚举转来，
 * 为空时会传 null）这一格会**静默变成空白**，看起来像页面坏了。
 */
function statusText(status) {
  return STATUS_TEXT[status] || status || '—'
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/session/taught')
    list.value = data.list || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 时间只取到分钟。
 * 用 `String(v).replace` 而不是 new Date()：后端给的是不含时区的
 * `2026-09-22T10:30:00`，交给 Date 解析会按浏览器时区偏移，反而可能差一天。
 */
function fmtTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : ''
}

/** 已结束的课优先看 endedAt；早期数据可能没有，往下退。 */
function rowTime(s) {
  return fmtTime(s.endedAt || s.startedAt || s.createdAt)
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="head">
      <h1 class="heading">课堂记录</h1>
      <p v-if="!loading && !error" class="sub">
        共 {{ list.length }} 节
        <template v-if="active.length"> · 进行中 {{ active.length }}</template>
        <template v-if="ended.length"> · 已结束 {{ ended.length }}</template>
      </p>
    </header>

    <FcLoading v-if="loading" text="加载课堂记录…" />

    <p v-else-if="error" class="err" role="alert">{{ error }}</p>

    <FcEmptyState
      v-else-if="!list.length"
      title="还没有上过课"
      description="在首页点「开始上课」，或者从课件详情页开一节课，记录就会出现在这里。"
    />

    <template v-else>
      <!-- 进行中的课排在前面：它们还能回去接着上 -->
      <section v-if="active.length" class="section">
        <h2 class="section__title">进行中</h2>
        <ul class="rows">
          <li v-for="s in active" :key="s.id" class="row">
            <div class="row__main">
              <span class="row__title">{{ s.title || '未命名课堂' }}</span>
              <span class="row__meta">
                {{ s.coursewareName || '课件已删除' }}
                <template v-if="rowTime(s)"> · {{ rowTime(s) }}</template>
              </span>
            </div>
            <span class="row__status row__status--on">{{ statusText(s.status) }}</span>
            <router-link class="btn-mini btn-mini--primary" :to="`/teach/${s.id}`">
              进入控制台
            </router-link>
          </li>
        </ul>
      </section>

      <section v-if="ended.length" class="section">
        <h2 class="section__title">已结束</h2>
        <ul class="rows">
          <li v-for="s in ended" :key="s.id" class="row">
            <div class="row__main">
              <span class="row__title">{{ s.title || '未命名课堂' }}</span>
              <span class="row__meta">
                {{ s.coursewareName || '课件已删除' }}
                <template v-if="rowTime(s)"> · {{ rowTime(s) }}</template>
              </span>
            </div>
            <span class="row__status">{{ statusText(s.status) }}</span>
            <router-link
              class="btn-mini"
              :to="{ name: 'session-record', params: { sessionId: s.id } }"
            >
              回顾
            </router-link>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  max-width: 880px;
  margin: 0 auto;
}

.head {
  margin-bottom: 22px;
}

.heading {
  font-size: 22px;
  color: var(--fc-text);
}

.sub {
  margin-top: 8px;
  font-size: 13px;
  color: var(--fc-text-faint);
}

.err {
  padding: 24px 0;
  font-size: 14px;
  color: var(--fc-danger);
}

.section {
  margin-bottom: 26px;
}

.section__title {
  margin-bottom: 10px;
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text-faint);
}

.rows {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  background: var(--fc-bg-panel);
}

.row__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.row__title {
  font-size: 14px;
  color: var(--fc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__meta {
  font-size: 12px;
  color: var(--fc-text-faint);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row__status {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--fc-text-faint);
}

.row__status--on {
  color: var(--fc-danger);
  font-weight: var(--fc-weight-semibold);
}

.btn-mini {
  flex-shrink: 0;
  padding: 5px 12px;
  border: 1px solid var(--fc-border-strong);
  border-radius: 7px;
  background: var(--fc-bg-panel);
  font-size: 12px;
  color: var(--fc-text);
  text-decoration: none;
}

.btn-mini:hover {
  border-color: var(--fc-primary);
  color: var(--fc-primary);
}

.btn-mini--primary {
  border-color: var(--fc-primary);
  background: var(--fc-primary);
  color: #fff;
}

.btn-mini--primary:hover {
  background: var(--fc-primary);
  color: #fff;
  filter: brightness(0.94);
}
</style>
