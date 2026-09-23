<script setup>
defineProps({
  title: { type: String, default: '' },
  /** 标题下方的灰色小字说明 */
  subtitle: { type: String, default: '' },
  /** none 用于内容自带内边距的场景（如表格铺满卡片） */
  padding: { type: String, default: 'md' },
  /** 鼠标悬停时抬起，用于可点击的卡片列表 */
  hoverable: { type: Boolean, default: false },
})
</script>

<template>
  <div class="fc-card" :class="[`fc-card--pad-${padding}`, { 'fc-card--hoverable': hoverable }]">
    <header v-if="title || $slots.header || $slots.extra" class="fc-card__header">
      <slot name="header">
        <div class="fc-card__titles">
          <h3 class="fc-card__title">{{ title }}</h3>
          <p v-if="subtitle" class="fc-card__subtitle">{{ subtitle }}</p>
        </div>
      </slot>
      <div v-if="$slots.extra" class="fc-card__extra">
        <slot name="extra" />
      </div>
    </header>

    <div class="fc-card__body">
      <slot />
    </div>

    <footer v-if="$slots.footer" class="fc-card__footer">
      <slot name="footer" />
    </footer>
  </div>
</template>

<style scoped>
.fc-card {
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  overflow: hidden;
  transition: box-shadow var(--fc-transition), border-color var(--fc-transition);
}

.fc-card--hoverable:hover {
  box-shadow: var(--fc-shadow);
  border-color: var(--fc-primary-border);
}

.fc-card--pad-md {
  /* 内边距交给各段自己控制，这里只标记尺寸 */
}
.fc-card--pad-none > .fc-card__body {
  padding: 0;
}
.fc-card--pad-sm > .fc-card__body {
  padding: var(--fc-space-3);
}
.fc-card--pad-md > .fc-card__body {
  padding: var(--fc-space-4);
}
.fc-card--pad-lg > .fc-card__body {
  padding: var(--fc-space-6);
}

.fc-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fc-space-3);
  padding: var(--fc-space-3) var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
}

.fc-card__title {
  font-size: var(--fc-font-md);
  font-weight: var(--fc-weight-semibold);
}

.fc-card__subtitle {
  margin-top: 2px;
  margin-bottom: 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.fc-card__extra {
  flex-shrink: 0;
}

.fc-card__footer {
  padding: var(--fc-space-3) var(--fc-space-4);
  border-top: 1px solid var(--fc-border);
  /* 原先写的是 var(--fc-bg-weak, transparent) —— tokens.css 里**没有** --fc-bg-weak，
     于是这一行一直在取兜底值 transparent，页脚底色从来没生效过。
     全库扫过，引用未定义令牌的只有这一处。 */
  background: var(--fc-bg-muted);
}
</style>
