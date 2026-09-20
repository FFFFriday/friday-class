<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** primary 主操作 / secondary 次操作 / danger 危险 / ghost 弱按钮 / text 纯文字 */
  variant: { type: String, default: 'primary' },
  /** sm / md / lg */
  size: { type: String, default: 'md' },
  /** 原生 button type。默认 button 而不是 submit：本组件多半在表单里当「取消」， */
  /** 忘了写 type 时 submit 会意外触发提交，这个默认值能兜住。 */
  nativeType: { type: String, default: 'button' },
  disabled: { type: Boolean, default: false },
  loading: { type: Boolean, default: false },
  /** 占满父容器宽度 */
  block: { type: Boolean, default: false },
})

const emit = defineEmits(['click'])

/**
 * loading 期间一并禁用。
 * 提交类操作最怕被点第二下——后端虽然有幂等键兜底，但前端不该把
 * 「能点」这个信号给出去。
 */
const isDisabled = computed(() => props.disabled || props.loading)

function onClick(event) {
  if (isDisabled.value) {
    return
  }
  emit('click', event)
}
</script>

<template>
  <button
    class="fc-btn"
    :class="[`fc-btn--${variant}`, `fc-btn--${size}`, { 'fc-btn--block': block }]"
    :type="nativeType"
    :disabled="isDisabled"
    :aria-busy="loading ? 'true' : undefined"
    @click="onClick"
  >
    <span v-if="loading" class="fc-btn__spinner" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
.fc-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--fc-space-2);
  border-radius: var(--fc-radius);
  font-weight: var(--fc-weight-medium);
  white-space: nowrap;
  transition: background var(--fc-transition), color var(--fc-transition),
    border-color var(--fc-transition), opacity var(--fc-transition);
}

.fc-btn--block {
  width: 100%;
}

.fc-btn:disabled {
  opacity: 0.55;
}

/* ── 尺寸 ── */
.fc-btn--sm {
  height: 28px;
  padding: 0 var(--fc-space-3);
  font-size: var(--fc-font-xs);
}
.fc-btn--md {
  height: 34px;
  padding: 0 var(--fc-space-4);
  font-size: var(--fc-font);
}
.fc-btn--lg {
  height: 42px;
  padding: 0 var(--fc-space-6);
  font-size: var(--fc-font-md);
}

/* ── 变体 ── */
.fc-btn--primary {
  background: var(--fc-primary);
  color: var(--fc-text-invert);
}
.fc-btn--primary:hover:not(:disabled) {
  background: var(--fc-primary-hover);
}

.fc-btn--secondary {
  background: var(--fc-bg-panel);
  color: var(--fc-text);
  border: 1px solid var(--fc-border-strong);
}
.fc-btn--secondary:hover:not(:disabled) {
  border-color: var(--fc-primary);
  color: var(--fc-primary);
}

.fc-btn--danger {
  background: var(--fc-danger);
  color: var(--fc-text-invert);
}
.fc-btn--danger:hover:not(:disabled) {
  background: var(--fc-danger-dark);
}

.fc-btn--ghost {
  background: var(--fc-primary-bg);
  color: var(--fc-primary);
  border: 1px solid var(--fc-primary-border);
}
.fc-btn--ghost:hover:not(:disabled) {
  background: var(--fc-primary-border);
}

.fc-btn--text {
  background: transparent;
  color: var(--fc-primary);
  padding-left: var(--fc-space-1);
  padding-right: var(--fc-space-1);
}
.fc-btn--text:hover:not(:disabled) {
  color: var(--fc-primary-hover);
  text-decoration: underline;
}

/* ── 加载圈 ── */
.fc-btn__spinner {
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: var(--fc-radius-circle);
  animation: fc-btn-spin 0.6s linear infinite;
}

@keyframes fc-btn-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
