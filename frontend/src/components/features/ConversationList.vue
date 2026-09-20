<script setup>
import { nextTick, ref } from 'vue'
import { FcButton, FcEmptyState, FcLoading } from '@/components/base'

defineProps({
  conversations: { type: Array, default: () => [] },
  activeId: { type: [Number, String], default: null },
  loading: { type: Boolean, default: false },
})

const emit = defineEmits(['select', 'create', 'rename', 'remove'])

/** 正在重命名的会话 ID。null = 没有在改名。 */
const editingId = ref(null)
const editingTitle = ref('')
const titleInput = ref(null)

async function startRename(conversation) {
  editingId.value = conversation.id
  editingTitle.value = conversation.title
  await nextTick()
  titleInput.value?.[0]?.focus?.()
}

function commitRename() {
  if (editingId.value == null) return
  const title = editingTitle.value.trim()
  if (title) {
    emit('rename', editingId.value, title)
  }
  editingId.value = null
}

function cancelRename() {
  editingId.value = null
}

function confirmRemove(conversation) {
  // 删除不可撤销，二次确认。用原生 confirm 而不是自绘弹窗：
  // 这里不需要额外信息，原生确认框更不容易被误点掉。
  if (window.confirm(`删除会话「${conversation.title}」？\n\n会话里的问答记录仍会保留在课堂记录中。`)) {
    emit('remove', conversation.id)
  }
}

function formatTime(value) {
  if (!value) return ''
  const match = /(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(value))
  if (!match) return ''
  const today = new Date()
  const isToday =
    Number(match[1]) === today.getFullYear() &&
    Number(match[2]) === today.getMonth() + 1 &&
    Number(match[3]) === today.getDate()
  // 今天的只显示时分，更早的带日期——列表里绝大多数是今天的
  return isToday ? `${match[4]}:${match[5]}` : `${match[2]}-${match[3]}`
}
</script>

<template>
  <div class="conv">
    <header class="conv__head">
      <span class="conv__title">我的会话</span>
      <FcButton size="sm" variant="ghost" @click="emit('create')">＋ 新建</FcButton>
    </header>

    <div class="conv__list">
      <FcLoading v-if="loading && !conversations.length" text="加载中…" />

      <FcEmptyState
        v-else-if="!conversations.length"
        size="sm"
        title="还没有会话"
        description="点「新建」开始一次新对话"
      />

      <div
        v-for="c in conversations"
        :key="c.id"
        class="conv__item"
        :class="{ 'conv__item--on': String(c.id) === String(activeId) }"
        @click="emit('select', c.id)"
      >
        <div class="conv__main">
          <input
            v-if="editingId === c.id"
            ref="titleInput"
            v-model="editingTitle"
            class="conv__rename"
            @click.stop
            @keyup.enter="commitRename"
            @keyup.esc="cancelRename"
            @blur="commitRename"
          />
          <span v-else class="conv__name">{{ c.title }}</span>

          <span class="conv__meta">
            <span v-if="c.messageCount">{{ c.messageCount }} 条</span>
            <span class="conv__time">{{ formatTime(c.lastActiveAt || c.createdAt) }}</span>
          </span>
        </div>

        <div v-if="editingId !== c.id" class="conv__ops" @click.stop>
          <button class="conv__op" type="button" title="重命名" @click="startRename(c)">改名</button>
          <button class="conv__op conv__op--danger" type="button" title="删除" @click="confirmRemove(c)">
            删除
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.conv {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.conv__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--fc-space-3) var(--fc-space-3) var(--fc-space-3) var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
}

.conv__title {
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text);
}

.conv__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--fc-space-2);
}

.conv__item {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  padding: var(--fc-space-2) var(--fc-space-3);
  border-radius: var(--fc-radius);
  cursor: pointer;
  transition: background var(--fc-transition);
}

.conv__item:hover {
  background: var(--fc-bg-muted);
}

.conv__item--on {
  background: var(--fc-primary-bg);
}

.conv__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.conv__name {
  font-size: var(--fc-font-sm);
  color: var(--fc-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv__item--on .conv__name {
  color: var(--fc-primary);
  font-weight: var(--fc-weight-medium);
}

.conv__meta {
  display: flex;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.conv__rename {
  width: 100%;
  padding: 2px 6px;
  border: 1px solid var(--fc-primary);
  border-radius: var(--fc-radius-sm);
  font-size: var(--fc-font-sm);
  background: var(--fc-bg-panel);
}

/* 操作按钮平时不显示，悬停或选中时才出现：
   列表里每行都挂两个按钮会很吵，而这两个操作都是低频的。 */
.conv__ops {
  display: none;
  gap: var(--fc-space-1);
  flex-shrink: 0;
}

.conv__item:hover .conv__ops,
.conv__item--on .conv__ops {
  display: flex;
}

.conv__op {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  padding: 1px 4px;
  border-radius: var(--fc-radius-sm);
}

.conv__op:hover {
  color: var(--fc-primary);
  background: var(--fc-bg-panel);
}

.conv__op--danger:hover {
  color: var(--fc-danger);
  background: var(--fc-danger-bg);
}
</style>
