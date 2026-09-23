<script setup>
// AI 助手页（/ai 与 /ai/:conversationId）—— 学生的长期 AI 会话。
//
// 【它和课堂里的 AI 面板有什么区别】
// 课堂里那个是「就当前这一页提问」，用完即走；这个页面是**长期会话**：
// 会话归学生本人，可建多个、可切换、可跨课延续，**下课后照样能用**。
//
// 【为什么 /ai 和 /ai/:id 是同一个页面】
// 用一个页面 + 会话侧栏，是这类产品的标准形态（豆包 / GPT 都是）。
// 拆成「列表页」和「聊天页」会导致同一套侧栏写两遍，
// 而且从列表点进会话会整页重挂载、丢滚动位置。
// /ai 只是「还没选会话」的那一屏，右侧换成空态而已。

import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useChatHistory } from '@/composables/useChatHistory'
import { useConversations } from '@/composables/useConversations'
import ConversationList from '@/components/features/ConversationList.vue'
import MessageBubble from '@/components/features/MessageBubble.vue'
import { FcButton, FcEmptyState, FcLoading, FcTag } from '@/components/base'

const route = useRoute()
const router = useRouter()

const {
  conversations,
  loading: listLoading,
  error: listError,
  load: loadConversations,
  create,
  rename,
  remove,
} = useConversations()

const {
  messages,
  loading: historyLoading,
  loadError,
  askError,
  notice,
  sending,
  cooldownLeft,
  load: loadHistory,
  reset: resetHistory,
  ask,
} = useChatHistory()

const draft = ref('')
const listEl = ref(null)

/** 从课件详情页「问 AI」过来时带上的上下文。 */
const coursewareId = computed(() => {
  const raw = route.query.coursewareId
  return raw ? Number(raw) : null
})
const pageId = computed(() => {
  const raw = route.query.pageId
  return raw ? Number(raw) : null
})

const activeId = computed(() => {
  const raw = route.params.conversationId
  return raw ? Number(raw) : null
})

const activeConversation = computed(
  () => conversations.value.find((c) => c.id === activeId.value) || null,
)

const canSend = computed(() => !sending.value && cooldownLeft.value === 0)

const placeholder = computed(() => {
  if (cooldownLeft.value > 0) return `${cooldownLeft.value} 秒后可提问`
  if (pageId.value) return '就这一页的内容提问…'
  return '问点什么…（回车发送）'
})

async function selectConversation(id) {
  if (id === activeId.value) return
  await router.push({ name: 'ai-chat', params: { conversationId: String(id) } })
}

async function newConversation() {
  const created = await create({
    coursewareId: coursewareId.value,
    sessionId: null,
  })
  if (created) {
    await router.push({ name: 'ai-chat', params: { conversationId: String(created.id) } })
  }
}

async function submit() {
  const text = draft.value.trim()
  if (!text || !canSend.value) return

  // 还没选会话就直接发：先自动建一个，再问。
  // 不这样的话学生要么先去点「新建」、要么这条消息被静默丢掉。
  let conversationId = activeId.value
  if (!conversationId) {
    const created = await create({ coursewareId: coursewareId.value, sessionId: null })
    if (!created) return
    conversationId = created.id
    await router.replace({ name: 'ai-chat', params: { conversationId: String(created.id) } })
  }

  draft.value = ''
  await ask(text, {
    conversationId,
    pageId: pageId.value,
    sessionId: null,
  })
  // 提问会刷新会话的活跃时间，列表顺序跟着变，顺手拉一次
  loadConversations()
}

/**
 * 自动滚到底。
 *
 * 只在「本来就贴着底」时才滚——学生正在往上翻历史时把他拽回底部会很烦。
 */
function isNearBottom() {
  const el = listEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 60
}

watch(
  () => messages.value.length,
  async (count, oldCount) => {
    if (!count) return
    const el = listEl.value
    if (!el) return
    // 首次载入（此前是空的）直接跳到底；之后只在「本来就贴着底」时才跟着滚。
    // 合成一个 watcher 而不是两个：两个都会在加载完成那一刻同时触发，
    // 一个判断 oldCount、一个判断 scrollTop，改起来容易顾此失彼。
    const stick = !oldCount || isNearBottom()
    await nextTick()
    if (stick) el.scrollTop = el.scrollHeight
  },
)

/** 路由里的会话 ID 变了（点侧栏、新建、退回 /ai）→ 换消息流。 */
watch(
  activeId,
  async (id) => {
    if (id) {
      await loadHistory(id)
      await nextTick()
      if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
    } else {
      resetHistory()
    }
  },
  { immediate: true },
)

onMounted(async () => {
  await loadConversations()
  // 直接进 /ai（没指定会话）时，自动选中最近聊过的那个。
  // 学生的预期是「回到上次的地方」，而不是看到一屏空白再自己找。
  if (!activeId.value && conversations.value.length) {
    router.replace({
      name: 'ai-chat',
      params: { conversationId: String(conversations.value[0].id) },
    })
  }
})
</script>

<template>
  <div class="ai">
    <aside class="ai__side">
      <ConversationList
        :conversations="conversations"
        :active-id="activeId"
        :loading="listLoading"
        @select="selectConversation"
        @create="newConversation"
        @rename="rename"
        @remove="remove"
      />
    </aside>

    <section class="ai__main">
      <header class="ai__head">
        <h1 class="ai__title">{{ activeConversation?.title || 'AI 问答' }}</h1>
        <div class="ai__tags">
          <FcTag v-if="pageId" type="primary" size="sm">就这一页提问</FcTag>
          <FcTag v-else-if="coursewareId" type="default" size="sm">关联某份课件</FcTag>
          <FcTag v-else type="default" size="sm">自由问答</FcTag>
        </div>
      </header>

      <p v-if="listError" class="ai__err" role="alert">{{ listError }}</p>

      <div ref="listEl" class="ai__body">
        <FcLoading v-if="historyLoading" text="加载消息…" />

        <FcEmptyState
          v-else-if="!activeId"
          size="lg"
          title="开始一次新对话"
          description="在左边新建一个会话，或者直接在下面提问"
        >
          <template #action>
            <FcButton @click="newConversation">新建会话</FcButton>
          </template>
        </FcEmptyState>

        <FcEmptyState
          v-else-if="!messages.length && !loadError"
          size="lg"
          title="还没有聊天记录"
          description="问点什么吧——这个会话会记住你说过的话"
        />

        <p v-if="loadError" class="ai__err">{{ loadError }}</p>

        <template v-for="m in messages" :key="m.id">
          <MessageBubble role="q" :text="m.question" />
          <MessageBubble role="a" :text="m.answer" :status="m.status" />
        </template>
      </div>

      <footer class="ai__foot">
        <p v-if="notice" class="ai__notice">{{ notice }}</p>
        <p v-if="askError" class="ai__err" role="alert">{{ askError }}</p>

        <div class="ai__input">
          <input
            v-model="draft"
            class="ai__field"
            :disabled="!canSend"
            :placeholder="placeholder"
            maxlength="500"
            @keyup.enter="submit"
          />
          <FcButton
            :disabled="!canSend || !draft.trim()"
            :loading="sending"
            @click="submit"
          >
            {{ sending ? '回答中…' : cooldownLeft ? `${cooldownLeft} 秒` : '发送' }}
          </FcButton>
        </div>
        <p class="ai__tip">
          每个会话的上下文是独立的，互不影响。想换个话题就新建一个会话。
        </p>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.ai {
  display: flex;
  gap: var(--fc-space-4);
  height: calc(100vh - 62px - 100px);
  min-height: 460px;
}

.ai__side {
  width: 260px;
  flex-shrink: 0;
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  overflow: hidden;
}

.ai__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  overflow: hidden;
}

.ai__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fc-space-3);
  padding: var(--fc-space-3) var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
}

.ai__title {
  font-size: var(--fc-font-md);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ai__tags {
  display: flex;
  gap: var(--fc-space-2);
  flex-shrink: 0;
}

.ai__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--fc-space-5);
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
  background: var(--fc-bg);
}

.ai__foot {
  padding: var(--fc-space-3) var(--fc-space-4);
  border-top: 1px solid var(--fc-border);
}

.ai__input {
  display: flex;
  gap: var(--fc-space-2);
}

.ai__field {
  flex: 1;
  min-width: 0;
  height: 36px;
  padding: 0 var(--fc-space-3);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font);
  background: var(--fc-bg-panel);
}
.ai__field:focus {
  outline: none;
  border-color: var(--fc-primary);
}
.ai__field:disabled {
  background: var(--fc-bg-muted);
  cursor: not-allowed;
}

.ai__tip {
  margin-top: var(--fc-space-2);
  margin-bottom: 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

/* 「本页暂无解析内容」这类临时提示。中性色，不是错误。 */
.ai__notice {
  font-size: var(--fc-font-sm);
  color: var(--fc-warning-text);
  background: var(--fc-warning-bg);
  padding: var(--fc-space-2) var(--fc-space-3);
  border-radius: var(--fc-radius);
  margin-bottom: var(--fc-space-2);
}

.ai__err {
  font-size: var(--fc-font-sm);
  color: var(--fc-danger);
  margin: 0;
}

@media (max-width: 900px) {
  .ai {
    flex-direction: column;
    height: auto;
  }
  .ai__side {
    width: 100%;
    max-height: 220px;
  }
  .ai__main {
    min-height: 420px;
  }
}
</style>
