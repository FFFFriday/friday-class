<script setup>
defineProps({
  title: { type: String, default: '暂无数据' },
  description: { type: String, default: '' },
  /** 内嵌用 sm，整页空态用 lg */
  size: { type: String, default: 'md' },
})
</script>

<template>
  <div class="fc-empty" :class="`fc-empty--${size}`">
    <div class="fc-empty__icon" aria-hidden="true">
      <slot name="icon">—</slot>
    </div>
    <p class="fc-empty__title">{{ title }}</p>
    <p v-if="description" class="fc-empty__desc">{{ description }}</p>
    <div v-if="$slots.action" class="fc-empty__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.fc-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  color: var(--fc-text-faint);
}

.fc-empty--sm {
  padding: var(--fc-space-4);
}
.fc-empty--md {
  padding: var(--fc-space-8) var(--fc-space-4);
}
.fc-empty--lg {
  padding: var(--fc-space-10) var(--fc-space-4);
}

.fc-empty__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  margin-bottom: var(--fc-space-3);
  border-radius: var(--fc-radius-circle);
  background: var(--fc-bg-muted);
  font-size: 22px;
  color: var(--fc-border-heavy);
}

.fc-empty__title {
  margin-bottom: 0;
  font-size: var(--fc-font);
  color: var(--fc-text-muted);
}

.fc-empty__desc {
  margin-top: var(--fc-space-1);
  margin-bottom: 0;
  max-width: 360px;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.fc-empty__action {
  margin-top: var(--fc-space-4);
}
</style>
