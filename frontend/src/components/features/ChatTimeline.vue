<script setup>
import { FcEmptyState, FcLoading, FcTag } from '@/components/base'

defineProps({
  messages: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  loadingMore: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const emit = defineEmits(['load-older'])

function formatTime(value) {
  if (!value) return ''
  // 后端给的是本地时间串（无时区后缀），用字符串切片，不做时区转换
  const match = /T(\d{2}):(\d{2})/.exec(String(value))
  return match ? `${match[1]}:${match[2]}` : ''
}
</script>

<template>
  <div class="timeline">
    <button
      v-if="hasMore"
      class="timeline__more"
      type="button"
      :disabled="loadingMore"
      @click="emit('load-older')"
    >
      {{ loadingMore ? '加载中…' : '加载更早的发言' }}
    </button>

    <FcLoading v-if="loading" text="加载发言…" />

    <FcEmptyState
      v-else-if="!messages.length && !error"
      size="sm"
      title="本堂课没有讨论区发言"
      description="学生在课堂里的发言会出现在这里"
    />

    <p v-if="error" class="timeline__err">{{ error }}</p>

    <ol class="timeline__list">
      <li v-for="m in messages" :key="m.id" class="timeline__item">
        <span class="timeline__time">{{ formatTime(m.createdAt) }}</span>

        <div class="timeline__body">
          <div class="timeline__meta">
            <span class="timeline__name">{{ m.nickname || '同学' }}</span>
            <FcTag v-if="m.role === 'TEACHER'" type="primary" size="sm">老师</FcTag>
            <span v-if="m.pageNo" class="timeline__page">第 {{ m.pageNo }} 页</span>
          </div>

          <!-- 文本插值：学生发言属不可信内容，严禁 v-html -->
          <p class="timeline__text" :class="{ 'timeline__text--gone': m.status === 'DELETED' }">
            {{ m.status === 'DELETED' ? '（该发言已被老师撤回）' : m.content }}
          </p>
        </div>
      </li>
    </ol>
  </div>
</template>

<style scoped>
.timeline {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
}

.timeline__more {
  align-self: center;
  padding: var(--fc-space-1) var(--fc-space-3);
  border-radius: var(--fc-radius-pill);
  background: var(--fc-bg-muted);
  color: var(--fc-text-muted);
  font-size: var(--fc-font-xs);
}
.timeline__more:hover:not(:disabled) {
  background: var(--fc-primary-bg);
  color: var(--fc-primary);
}

.timeline__list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
}

.timeline__item {
  display: flex;
  gap: var(--fc-space-3);
  align-items: flex-start;
}

/* 时间在左、内容在右。固定宽度让时间列对齐，扫一眼就能看出节奏。 */
.timeline__time {
  flex-shrink: 0;
  width: 42px;
  padding-top: 2px;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  font-variant-numeric: tabular-nums;
}

.timeline__body {
  flex: 1;
  min-width: 0;
}

.timeline__meta {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  margin-bottom: 3px;
  font-size: var(--fc-font-xs);
}

.timeline__name {
  color: var(--fc-text-muted);
  font-weight: var(--fc-weight-medium);
}

.timeline__page {
  color: var(--fc-primary);
  background: var(--fc-primary-bg);
  border-radius: var(--fc-radius-sm);
  padding: 0 5px;
}

.timeline__text {
  margin: 0;
  font-size: var(--fc-font-sm);
  line-height: 1.65;
  color: var(--fc-text);
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.timeline__text--gone {
  color: var(--fc-text-faint);
  font-style: italic;
}

.timeline__err {
  font-size: var(--fc-font-sm);
  color: var(--fc-danger);
  margin: 0;
}
</style>
