<script setup>
defineProps({
  /** 宽度，数字按 px 处理，也接受 CSS 字符串（如 '60%'） */
  width: { type: [String, Number], default: '100%' },
  height: { type: [String, Number], default: 16 },
  /** 渲染几个（列表骨架用） */
  count: { type: Number, default: 1 },
  /** 圆形骨架（头像位） */
  circle: { type: Boolean, default: false },
  radius: { type: String, default: '' },
})

function toSize(value) {
  return typeof value === 'number' ? `${value}px` : value
}
</script>

<template>
  <div class="fc-skeleton-group" :class="{ 'fc-skeleton-group--gap': count > 1 }">
    <div
      v-for="i in count"
      :key="i"
      class="fc-skeleton"
      :class="{ 'fc-skeleton--circle': circle }"
      :style="{
        width: toSize(width),
        height: toSize(circle && height === 16 ? width : height),
        borderRadius: circle ? undefined : radius || undefined,
      }"
      aria-hidden="true"
    />
  </div>
</template>

<style scoped>
.fc-skeleton-group {
  display: flex;
  flex-direction: column;
}
.fc-skeleton-group--gap {
  gap: var(--fc-space-3);
}

.fc-skeleton {
  border-radius: var(--fc-radius-sm);
  /* 用渐变扫动而不是逐个元素做 animation-delay：
     多个骨架共用同一个动画周期，看起来是「一束光扫过整块列表」，
     比各自为政的闪烁干净得多。 */
  background: linear-gradient(
    90deg,
    var(--fc-bg-muted) 25%,
    #ebebeb 37%,
    var(--fc-bg-muted) 63%
  );
  background-size: 400% 100%;
  animation: fc-skeleton-sweep 1.4s ease infinite;
}

.fc-skeleton--circle {
  border-radius: var(--fc-radius-circle);
}

@keyframes fc-skeleton-sweep {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
</style>
