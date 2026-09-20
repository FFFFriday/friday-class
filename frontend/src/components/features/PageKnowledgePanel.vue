<script setup>
import { computed } from 'vue'
import { FcEmptyState, FcLoading } from '@/components/base'

const props = defineProps({
  /** 当前页的 PagePack：{ pageId, pageNo, knowledgePoints[], presetQuestions[] }，可能为 null */
  pack: { type: Object, default: null },
  /** 课件解析状态：null / 'PENDING' / 'RUNNING' / 'PARSING' / 'SUCCESS' / 'PARTIAL' / 'FAILED' */
  parseStatus: { type: String, default: null },
  /** 该课件是否成功解析过（parseVersion > 0） */
  parsed: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  pageNo: { type: [Number, String], default: null },
})

const emit = defineEmits(['use-question'])

const knowledgePoints = computed(() => props.pack?.knowledgePoints || [])
const presetQuestions = computed(() => props.pack?.presetQuestions || [])

const hasAnything = computed(
  () => knowledgePoints.value.length > 0 || presetQuestions.value.length > 0,
)

/**
 * 空态必须分情况说清楚，**不能一律显示空白**。
 *
 * 只服务于「喂给 AI」时，空数据没人会察觉；一旦显示给学生看，
 * 「解析中」与「这页真没内容」长得一模一样，学生只会以为系统坏了。
 * 所以这里按状态给三种不同的说法。
 */
const emptyState = computed(() => {
  if (props.loading) return null
  if (props.parseStatus === 'PARSING' || props.parseStatus === 'RUNNING'
      || props.parseStatus === 'PENDING') {
    return { title: '课件正在解析中', desc: '解析完成后，这一页的知识点会自动出现' }
  }
  if (props.parseStatus === 'FAILED') {
    return { title: '这份课件解析失败了', desc: '可以请老师重新解析一次' }
  }
  if (!props.parsed) {
    return { title: '老师还没有解析这份课件', desc: '解析之后，这里会显示 AI 提取的知识点' }
  }
  return { title: '本页暂无知识点', desc: '这一页可能没有可提取的文字内容' }
})
</script>

<template>
  <div class="kp">
    <FcLoading v-if="loading" text="读取知识点…" />

    <FcEmptyState
      v-else-if="!hasAnything && emptyState"
      size="sm"
      :title="emptyState.title"
      :description="emptyState.desc"
    />

    <template v-else>
      <section v-if="knowledgePoints.length" class="kp__section">
        <h4 class="kp__title">
          本页知识点
          <span v-if="pageNo" class="kp__page">第 {{ pageNo }} 页</span>
        </h4>
        <ul class="kp__list">
          <li v-for="(point, i) in knowledgePoints" :key="i" class="kp__item">
            <span class="kp__dot" aria-hidden="true">{{ i + 1 }}</span>
            <!-- 文本插值。知识点来自课件正文，同样属于不可信内容 -->
            <span class="kp__text">{{ point }}</span>
          </li>
        </ul>
      </section>

      <section v-if="presetQuestions.length" class="kp__section">
        <h4 class="kp__title">本页思考题</h4>
        <p class="kp__hint">点一下填进提问框，可以改完再发</p>
        <div class="kp__chips">
          <button
            v-for="(q, i) in presetQuestions"
            :key="i"
            class="kp__chip"
            type="button"
            @click="emit('use-question', q)"
          >
            {{ q }}
          </button>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.kp {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-4);
  padding: var(--fc-space-4);
  overflow-y: auto;
  min-height: 0;
}

.kp__title {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-xs);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-primary);
  margin-bottom: var(--fc-space-2);
}

.kp__page {
  font-size: var(--fc-font-xs);
  font-weight: 400;
  color: var(--fc-text-faint);
}

.kp__list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
}

.kp__item {
  display: flex;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-sm);
  line-height: 1.65;
  color: var(--fc-text);
}

.kp__dot {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  border-radius: var(--fc-radius-circle);
  background: var(--fc-primary-bg);
  color: var(--fc-primary);
  font-size: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.kp__text {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}

.kp__hint {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  margin-bottom: var(--fc-space-2);
}

.kp__chips {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
}

.kp__chip {
  text-align: left;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-muted);
  background: var(--fc-bg);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius-lg);
  padding: var(--fc-space-2) var(--fc-space-3);
  line-height: 1.6;
  transition: border-color var(--fc-transition), color var(--fc-transition),
    background var(--fc-transition);
}

.kp__chip:hover {
  border-color: var(--fc-primary-border);
  color: var(--fc-primary);
  background: var(--fc-primary-bg-weak);
}
</style>
