<script setup>
defineProps({
  /** default 灰 / primary 品牌 / success 绿 / warning 黄 / danger 红 */
  type: { type: String, default: 'default' },
  size: { type: String, default: 'md' }, // sm / md
})
</script>

<template>
  <span class="fc-tag" :class="[`fc-tag--${type}`, `fc-tag--${size}`]">
    <slot />
  </span>
</template>

<style scoped>
.fc-tag {
  display: inline-flex;
  align-items: center;
  gap: var(--fc-space-1);
  border-radius: var(--fc-radius-sm);
  border: 1px solid transparent;
  font-weight: var(--fc-weight-medium);
  white-space: nowrap;
  line-height: 1.4;
}

.fc-tag--sm {
  padding: 0 var(--fc-space-1);
  font-size: 11px;
}
.fc-tag--md {
  padding: 1px var(--fc-space-2);
  font-size: var(--fc-font-xs);
}

.fc-tag--default {
  background: var(--fc-bg-muted);
  color: var(--fc-text-muted);
  border-color: var(--fc-border);
}

.fc-tag--primary {
  background: var(--fc-primary-bg);
  color: var(--fc-primary);
  border-color: var(--fc-primary-border);
}

.fc-tag--success {
  background: var(--fc-success-bg);
  color: var(--fc-success);
  border-color: #c8ecd8;
}

.fc-tag--warning {
  background: var(--fc-warning-bg);
  color: var(--fc-warning-text);
  border-color: var(--fc-warning-border);
}

.fc-tag--danger {
  background: var(--fc-danger-bg);
  color: var(--fc-danger);
  border-color: #f5c6c0;
}
</style>
