<script setup>
import { ref } from 'vue'
import { FcEmptyState, FcLoading, FcTag } from '@/components/base'

defineProps({
  /** [{ userId, nickname, askCount, records: [...] }] */
  groups: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

/** 展开的学生 ID 集合。默认展开第一个——一进来就该看到点东西，而不是一列折叠条。 */
const expanded = ref(new Set())

function isOpen(userId) {
  return expanded.value.has(userId)
}

function toggle(userId) {
  const next = new Set(expanded.value)
  if (next.has(userId)) {
    next.delete(userId)
  } else {
    next.add(userId)
  }
  expanded.value = next
}

function formatTime(value) {
  if (!value) return ''
  const match = /T(\d{2}):(\d{2})/.exec(String(value))
  return match ? `${match[1]}:${match[2]}` : ''
}
</script>

<template>
  <div class="sqa">
    <FcLoading v-if="loading" text="加载问答记录…" />

    <!--
      「没跟 AI 聊过的学生不显示」是明确需求。后端从问答记录出发分组，
      没记录的学生根本构不出这一组——所以这里不需要任何过滤逻辑，
      空的时候就是真的「本堂课没有学生问过 AI」。
    -->
    <FcEmptyState
      v-else-if="!groups.length && !error"
      size="sm"
      title="本堂课没有学生向 AI 提过问"
      description="学生与 AI 助手的对话会按人归类显示在这里"
    />

    <p v-if="error" class="sqa__err">{{ error }}</p>

    <div v-for="(g, i) in groups" :key="g.userId" class="sqa__group">
      <button class="sqa__head" type="button" :aria-expanded="isOpen(g.userId)" @click="toggle(g.userId)">
        <span class="sqa__caret" :class="{ 'sqa__caret--open': isOpen(g.userId) || i === 0 }">▸</span>
        <span class="sqa__name">{{ g.nickname || '同学' }}</span>
        <FcTag type="default" size="sm">{{ g.askCount }} 次提问</FcTag>
      </button>

      <!-- 默认展开第一组；其余折叠，点开才显示。 -->
      <div v-show="isOpen(g.userId) || (i === 0 && !expanded.size)" class="sqa__body">
        <div v-for="r in g.records" :key="r.id" class="sqa__item">
          <p class="sqa__q">
            <span v-if="r.pageNo" class="sqa__page">第 {{ r.pageNo }} 页</span>
            <span v-if="r.askedAt" class="sqa__time">{{ formatTime(r.askedAt) }}</span>
            {{ r.question }}
          </p>
          <!-- 文本插值：回答来自模型，同样不当作 HTML -->
          <p class="sqa__a" :class="{ 'sqa__a--failed': r.status === 'FAILED' }">{{ r.answer }}</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.sqa {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
}

.sqa__group {
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  overflow: hidden;
}

.sqa__head {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  width: 100%;
  padding: var(--fc-space-3) var(--fc-space-4);
  background: var(--fc-bg-panel);
  text-align: left;
  transition: background var(--fc-transition);
}

.sqa__head:hover {
  background: var(--fc-bg-muted);
}

.sqa__caret {
  color: var(--fc-text-faint);
  font-size: 11px;
  transition: transform var(--fc-transition);
}

.sqa__caret--open {
  transform: rotate(90deg);
}

.sqa__name {
  flex: 1;
  min-width: 0;
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-medium);
  color: var(--fc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sqa__body {
  padding: var(--fc-space-3) var(--fc-space-4);
  border-top: 1px solid var(--fc-border);
  background: var(--fc-bg);
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
}

.sqa__item {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.sqa__q {
  display: flex;
  align-items: baseline;
  gap: var(--fc-space-2);
  margin: 0;
  font-size: var(--fc-font-sm);
  color: var(--fc-text);
  line-height: 1.6;
}

.sqa__page {
  flex-shrink: 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-primary);
  background: var(--fc-primary-bg);
  border-radius: var(--fc-radius-sm);
  padding: 0 5px;
}

.sqa__time {
  flex-shrink: 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  font-variant-numeric: tabular-nums;
}

.sqa__a {
  margin: 0;
  padding: var(--fc-space-2) var(--fc-space-3);
  border-radius: var(--fc-radius);
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.sqa__a--failed {
  background: var(--fc-warning-bg);
  border-color: var(--fc-warning-border);
  color: var(--fc-warning-text);
}

.sqa__err {
  font-size: var(--fc-font-sm);
  color: var(--fc-danger);
  margin: 0;
}
</style>
