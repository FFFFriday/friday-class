<script setup>
defineProps({
  text: { type: String, default: '加载中…' },
  size: { type: String, default: 'md' }, // sm / md / lg
  /**
   * 铺满父容器并居中。
   * 注意是 absolute 而非 fixed：它相对最近的定位祖先铺开，
   * 所以既能用于整页，也能用于一张卡片内部。
   */
  overlay: { type: Boolean, default: false },
  /** 配合 overlay 使用，给遮罩加白色底（不透明底会挡住底下的骨架） */
  opaque: { type: Boolean, default: true },
})
</script>

<template>
  <div class="fc-loading" :class="[`fc-loading--${size}`, { 'fc-loading--overlay': overlay }]">
    <div
      v-if="overlay && opaque"
      class="fc-loading__mask"
      aria-hidden="true"
    />
    <div class="fc-loading__inner">
      <span class="fc-loading__spinner" aria-hidden="true" />
      <span v-if="text" class="fc-loading__text">{{ text }}</span>
    </div>
  </div>
</template>

<style scoped>
.fc-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--fc-space-6);
  color: var(--fc-text-faint);
}

.fc-loading--overlay {
  position: absolute;
  inset: 0;
  z-index: var(--fc-z-dropdown);
  padding: 0;
}

.fc-loading__mask {
  position: absolute;
  inset: 0;
  background: rgba(255, 255, 255, 0.75);
}

.fc-loading__inner {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-sm);
}

.fc-loading__spinner {
  display: inline-block;
  border: 2px solid var(--fc-border-strong);
  border-top-color: var(--fc-primary);
  border-radius: var(--fc-radius-circle);
  animation: fc-loading-spin 0.7s linear infinite;
}

.fc-loading--sm .fc-loading__spinner {
  width: 14px;
  height: 14px;
}
.fc-loading--md .fc-loading__spinner {
  width: 20px;
  height: 20px;
}
.fc-loading--lg .fc-loading__spinner {
  width: 28px;
  height: 28px;
  border-width: 3px;
}

@keyframes fc-loading-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
