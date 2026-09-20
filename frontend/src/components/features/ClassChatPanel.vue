<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { FcButton, FcEmptyState, FcLoading, FcTag } from '@/components/base'
import { MAX_CONTENT_LENGTH } from '@/composables/useClassChat'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  loadingMore: { type: Boolean, default: false },
  hasMore: { type: Boolean, default: false },
  loadError: { type: String, default: '' },
  sendError: { type: String, default: '' },
  /** 限流冷却剩余秒数。大于 0 时禁用发送。 */
  cooldownLeft: { type: Number, default: 0 },
  /** 自己是谁。用来把自己的发言靠右显示。 */
  selfId: { type: [Number, String], default: null },
  /** 已下课：禁止发言。 */
  ended: { type: Boolean, default: false },
  /** 课堂被管理员暂停：禁止发言。 */
  paused: { type: Boolean, default: false },
  /** 是否显示「撤回」按钮（老师/管理员）。真正的权限边界在后端。 */
  canDelete: { type: Boolean, default: false },
})

const emit = defineEmits(['send', 'delete', 'load-older'])

const draft = ref('')
const listEl = ref(null)

const canSend = computed(() => !props.ended && !props.paused && props.cooldownLeft === 0)

const placeholder = computed(() => {
  if (props.ended) return '本节课已结束'
  if (props.paused) return '课堂已暂停'
  if (props.cooldownLeft > 0) return `${props.cooldownLeft} 秒后可发言`
  return '说点什么…（回车发送）'
})

const buttonText = computed(() => {
  if (props.cooldownLeft > 0) return `${props.cooldownLeft} 秒`
  return '发送'
})

const remaining = computed(() => MAX_CONTENT_LENGTH - draft.value.length)

function submit() {
  const text = draft.value.trim()
  if (!text || !canSend.value) return
  emit('send', text)
  draft.value = ''
}

function formatTime(value) {
  if (!value) return ''
  // 后端给的是 "2026-09-20T22:05:11"（本地时间，无时区后缀）。
  // 直接 new Date() 解析这种串在 Safari 上会得到 Invalid Date，
  // 所以用字符串切片取时分，不做时区转换——本来就是本地时间。
  const match = /T(\d{2}):(\d{2})/.exec(String(value))
  return match ? `${match[1]}:${match[2]}` : ''
}

/**
 * 新消息进来时滚到底部。
 *
 * 只在「本来就在底部附近」时才自动滚——学生正在往上翻历史时，
 * 硬把他拽回底部会让人烦躁。40px 是「差不多贴着底」的经验阈值。
 */
function isNearBottom() {
  const el = listEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 40
}

watch(
  () => props.messages.length,
  async (_newLen, oldLen) => {
    // 首次加载不滚（用户还没开始看），追加时才滚
    if (!oldLen) return
    const el = listEl.value
    if (!el) return
    const shouldStick = isNearBottom()
    await nextTick()
    if (shouldStick) {
      el.scrollTop = el.scrollHeight
    }
  },
)

/** 初次加载完成后直接跳到底部。 */
watch(
  () => props.loading,
  async (now, before) => {
    if (before && !now) {
      await nextTick()
      if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
    }
  },
)
</script>

<template>
  <div class="chat">
    <header class="chat__head">
      <span class="chat__title">课堂讨论</span>
      <span class="chat__count">{{ messages.length }} 条</span>
    </header>

    <div ref="listEl" class="chat__list">
      <button
        v-if="hasMore"
        class="chat__more"
        type="button"
        :disabled="loadingMore"
        @click="emit('load-older')"
      >
        {{ loadingMore ? '加载中…' : '加载更早的发言' }}
      </button>

      <FcLoading v-if="loading" text="加载讨论…" />

      <FcEmptyState
        v-else-if="!messages.length && !loadError"
        size="sm"
        title="还没有人发言"
        description="在这里提问或讨论，全班都能看到"
      />

      <p v-if="loadError" class="chat__err">{{ loadError }}</p>

      <div
        v-for="m in messages"
        :key="m.id"
        class="msg"
        :class="{ 'msg--self': String(m.userId) === String(selfId), 'msg--deleted': m.status === 'DELETED' }"
      >
        <div class="msg__meta">
          <span class="msg__name">{{ m.nickname || '同学' }}</span>
          <FcTag v-if="m.role === 'TEACHER'" type="primary" size="sm">老师</FcTag>
          <span v-if="m.pageNo" class="msg__page">第 {{ m.pageNo }} 页</span>
          <span class="msg__time">{{ formatTime(m.createdAt) }}</span>
          <button
            v-if="canDelete && m.status !== 'DELETED'"
            class="msg__del"
            type="button"
            @click="emit('delete', m.id)"
          >
            撤回
          </button>
        </div>

        <!--
          ⚠️ 安全铁律：这里**必须**是文本插值 {{ }}。
          内容原样来自用户输入，用 v-html 就等于给全班开了 XSS 后门。
          改这一行之前请先读 M2 分册的「关于 XSS 的硬性要求」。
        -->
        <p class="msg__text" :class="{ 'msg__text--gone': m.status === 'DELETED' }">
          {{ m.status === 'DELETED' ? '该发言已被老师撤回' : m.content }}
        </p>
      </div>
    </div>

    <div class="chat__foot">
      <p v-if="sendError" class="chat__err chat__err--foot" role="alert">{{ sendError }}</p>

      <div class="chat__input">
        <input
          v-model="draft"
          class="chat__field"
          :disabled="!canSend"
          :placeholder="placeholder"
          :maxlength="MAX_CONTENT_LENGTH"
          @keyup.enter="submit"
        />
        <FcButton size="sm" :disabled="!canSend || !draft.trim()" @click="submit">
          {{ buttonText }}
        </FcButton>
      </div>

      <span v-if="draft.length > MAX_CONTENT_LENGTH - 100" class="chat__remaining">
        还可以输入 {{ remaining }} 字
      </span>
    </div>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
}

.chat__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--fc-space-3) var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
}

.chat__title {
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text);
}

.chat__count {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.chat__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--fc-space-3) var(--fc-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
}

.chat__more {
  align-self: center;
  padding: var(--fc-space-1) var(--fc-space-3);
  border-radius: var(--fc-radius-pill);
  background: var(--fc-bg-muted);
  color: var(--fc-text-muted);
  font-size: var(--fc-font-xs);
}
.chat__more:hover:not(:disabled) {
  background: var(--fc-primary-bg);
  color: var(--fc-primary);
}

.msg {
  display: flex;
  flex-direction: column;
  gap: 3px;
  max-width: 100%;
}

.msg__meta {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.msg--self .msg__meta {
  flex-direction: row-reverse;
}

.msg__name {
  color: var(--fc-text-muted);
  font-weight: var(--fc-weight-medium);
}

.msg__page {
  color: var(--fc-primary);
  background: var(--fc-primary-bg);
  border-radius: var(--fc-radius-sm);
  padding: 0 5px;
}

.msg__time {
  font-variant-numeric: tabular-nums;
}

.msg__del {
  color: var(--fc-danger);
  font-size: var(--fc-font-xs);
  padding: 0 var(--fc-space-1);
  opacity: 0.75;
}
.msg__del:hover {
  opacity: 1;
  text-decoration: underline;
}

.msg__text {
  margin: 0;
  padding: var(--fc-space-2) var(--fc-space-3);
  border-radius: var(--fc-radius);
  background: var(--fc-bg-muted);
  font-size: var(--fc-font-sm);
  line-height: 1.6;
  color: var(--fc-text);
  /* 长串英文/URL 不换行会把面板撑破 */
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.msg--self .msg__text {
  background: var(--fc-primary-bg);
  align-self: flex-end;
}

.msg__text--gone {
  color: var(--fc-text-faint);
  font-style: italic;
  background: var(--fc-bg-muted);
}

.chat__foot {
  padding: var(--fc-space-3) var(--fc-space-4);
  border-top: 1px solid var(--fc-border);
}

.chat__input {
  display: flex;
  gap: var(--fc-space-2);
}

.chat__field {
  flex: 1;
  min-width: 0;
  height: 30px;
  padding: 0 var(--fc-space-3);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
  background: var(--fc-bg-panel);
}
.chat__field:focus {
  outline: none;
  border-color: var(--fc-primary);
}
.chat__field:disabled {
  background: var(--fc-bg-muted);
  cursor: not-allowed;
}

.chat__remaining {
  display: block;
  margin-top: var(--fc-space-1);
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  text-align: right;
}

.chat__err {
  font-size: var(--fc-font-xs);
  color: var(--fc-danger);
  margin: 0;
}
.chat__err--foot {
  margin-bottom: var(--fc-space-2);
}
</style>
