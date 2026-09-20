<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 数字，或任意短文本（如 'NEW'） */
  value: { type: [Number, String], default: '' },
  /** 超过这个数显示成 "99+"。0 表示不限。 */
  max: { type: Number, default: 99 },
  /** 只显示一个小圆点，不显示数字 */
  dot: { type: Boolean, default: false },
  type: { type: String, default: 'danger' },
})

const display = computed(() => {
  const { value, max, dot } = props
  if (dot || value === '' || value === null || value === undefined) {
    return ''
  }
  if (typeof value === 'number' && max > 0 && value > max) {
    return `${max}+`
  }
  return String(value)
})

const hidden = computed(() => !props.dot && display.value === '')
</script>

<template>
  <span v-if="!hidden" class="fc-badge" :class="[`fc-badge--${type}`, { 'fc-badge--dot': dot }]">
    {{ display }}
  </span>
</template>

<style scoped>
.fc-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 16px;
  height: 16px;
  padding: 0 5px;
  border-radius: var(--fc-radius-pill);
  font-size: 11px;
  font-weight: var(--fc-weight-semibold);
  line-height: 1;
  color: var(--fc-text-invert);
}

.fc-badge--dot {
  min-width: 8px;
  width: 8px;
  height: 8px;
  padding: 0;
}

.fc-badge--danger {
  background: var(--fc-danger);
}
.fc-badge--primary {
  background: var(--fc-primary);
}
.fc-badge--success {
  background: var(--fc-success);
}
.fc-badge--warning {
  background: var(--fc-warning);
}
</style>
