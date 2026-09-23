<script setup>
// 课件卡片。教师首页、学生首页、课件中心三处共用。
//
// 没有课件封面图（后端不产出缩略图，也不打算为 MVP 引入图片资源），
// 所以封面区用品牌色调的色块 + 课件名前几个字顶上，靠排版撑出「课程卡片」的观感，
// 而不是留一个空框或灰色占位图。
//
// ── 关于卡片结构 ─────────────────────────────────────────────
// 卡片**不是**一个包住全部内容的大 <a>，而是「链接主体 + 底部操作区」两段。
// 原因：HTML 规定 <a> 不能内嵌交互元素（<button> / 另一个 <a>），
// 而「我的课件」的卡片右下角要放一个「上课」按钮。硬塞进去虽然浏览器能渲染，
// 但属于非法结构，屏幕阅读器与键盘 Tab 顺序都会出问题。
//
// 操作区由调用方通过 `action` 插槽填：
//   - 传了插槽 → 底部多出一行（教师首页「我的课件」）
//   - 不传     → 完全不渲染（课件中心、学生首页保持原样）
// 按钮在链接之外，所以点它不会连带触发卡片跳转，调用方也不必写 @click.stop。
import { computed } from 'vue'

const props = defineProps({
  courseware: { type: Object, required: true },
})

const statusText = {
  UPLOADED: '已上传',
  CONVERTING: '转换中',
  CONVERTED: '已转换',
  PARSING: '解析中',
  PARSED: '已解析',
  FAILED: '失败',
}

const statusClass = computed(() => (props.courseware.status || '').toLowerCase() || 'uploaded')
const statusLabel = computed(() => statusText[props.courseware.status] || props.courseware.status || '未知')

// 「数据结构 · 第一章 绪论.pptx」→「数据结构」
const coverText = computed(() => {
  const raw = (props.courseware.name || '').replace(/\.[^.]+$/, '')
  const first = raw.split(/[·\-_（(\s]/)[0].trim()
  return (first || raw).slice(0, 4) || '课件'
})

const uploadedDate = computed(() => {
  const raw = props.courseware.uploadedAt
  if (!raw) return ''
  return String(raw).slice(0, 10)
})
</script>

<template>
  <div class="cw-card">
    <router-link class="cw-card__link" :to="`/courseware/${courseware.id}`">
      <div class="cover">
        <span class="cover-text">{{ coverText }}</span>
        <span class="status-pill" :class="statusClass">{{ statusLabel }}</span>
      </div>
      <div class="body">
        <h3 class="name" :title="courseware.name">{{ courseware.name }}</h3>
        <p class="meta">
          <span>{{ courseware.uploaderName || '未知上传者' }}</span>
          <span class="dot">·</span>
          <span>{{ courseware.pageCount ?? 0 }} 页</span>
        </p>
        <p v-if="uploadedDate" class="date">{{ uploadedDate }}</p>
      </div>
    </router-link>

    <!-- 底部操作区（可选）。见文件头注释：必须留在链接外面。 -->
    <div v-if="$slots.action" class="actions">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.cw-card {
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 10px;
  overflow: hidden;
  transition: box-shadow 0.18s ease, transform 0.18s ease;
}

.cw-card:hover {
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.09);
  transform: translateY(-2px);
}

/* 链接主体撑满卡片，卡片高度由 .body 的 flex:1 拉齐（一行卡片高度整齐） */
.cw-card__link {
  display: flex;
  flex-direction: column;
  flex: 1;
  text-decoration: none;
  color: inherit;
}

.cover {
  position: relative;
  height: 104px;
  padding: 18px 16px;
  background: linear-gradient(135deg, #fdf6f2 0%, #f9e6dd 100%);
  display: flex;
  align-items: flex-end;
}

.cover-text {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: 2px;
  color: #d97757;
}

.status-pill {
  position: absolute;
  top: 12px;
  right: 12px;
  font-size: 12px;
  line-height: 1;
  padding: 5px 9px;
  border-radius: 20px;
  background: #fff3e6;
  color: #e67e22;
}

.status-pill.parsed {
  background: #e8f7ee;
  color: #27ae60;
}

.status-pill.failed {
  background: #fdeaea;
  color: #e74c3c;
}

.body {
  flex: 1;
  padding: 12px 14px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.name {
  font-size: 14px;
  font-weight: 600;
  color: #333;
  line-height: 1.4;
  /* 两行封顶，保证一行卡片高度整齐 */
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.meta {
  font-size: 12px;
  color: #999;
  display: flex;
  gap: 5px;
}

.date {
  font-size: 12px;
  color: #bbb;
}

/* 底部操作区。只在调用方传了 action 插槽时渲染，所以默认的课件中心/学生首页
   卡片外观完全不变。 */
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 0 14px 14px;
}
</style>
