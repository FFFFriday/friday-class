<script setup>
import { useToast } from '@/composables/useToast'

const { toasts, dismiss } = useToast()
</script>

<template>
  <Teleport to="body">
    <div class="fc-toast-layer" aria-live="polite" aria-atomic="false">
      <TransitionGroup name="fc-toast">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          class="fc-toast"
          :class="`fc-toast--${toast.type}`"
          role="status"
        >
          <span class="fc-toast__text">{{ toast.message }}</span>
          <button class="fc-toast__close" type="button" aria-label="关闭提示" @click="dismiss(toast.id)">
            ×
          </button>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
/*
 * 全局唯一的提示容器。pointer-events: none 让这一层不挡鼠标，
 * 单条提示自己再打开 pointer-events —— 否则透明的容器会盖住整个页面，
 * 用户点什么都没反应。
 */
.fc-toast-layer {
  position: fixed;
  top: var(--fc-space-6);
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--fc-z-toast);
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
  pointer-events: none;
}

.fc-toast {
  display: flex;
  align-items: center;
  gap: var(--fc-space-3);
  min-width: 200px;
  max-width: 420px;
  padding: var(--fc-space-2) var(--fc-space-3) var(--fc-space-2) var(--fc-space-4);
  border-radius: var(--fc-radius);
  border-left: 3px solid transparent;
  background: var(--fc-bg-panel);
  box-shadow: var(--fc-shadow-lg);
  font-size: var(--fc-font-sm);
  pointer-events: auto;
}

.fc-toast__text {
  flex: 1;
  word-break: break-word;
}

.fc-toast__close {
  position: relative;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: var(--fc-radius-sm);
  font-size: 16px;
  line-height: 1;
  color: var(--fc-text-faint);
  transition:
    background var(--fc-transition),
    color var(--fc-transition),
    transform var(--fc-dur-press) var(--fc-ease-out);
}
.fc-toast__close:hover {
  background: var(--fc-bg-muted);
  color: var(--fc-text);
}
.fc-toast__close:active {
  transform: scale(0.88);
}

/* 视觉 20px，命中区撑到 36px（四周各扩 8px）。
   这里是 36 而不是通用的 40：提示条本身才 36px 高，再往外扩，
   「能点到」的范围就跑到提示条外面去了，反而更怪。 */
.fc-toast__close::before {
  content: '';
  position: absolute;
  inset: -8px;
}

.fc-toast--success {
  border-left-color: var(--fc-success);
}
.fc-toast--info {
  border-left-color: var(--fc-primary);
}
.fc-toast--warning {
  border-left-color: var(--fc-warning);
}
.fc-toast--error {
  border-left-color: var(--fc-danger);
}

/* ── 进出场 ───────────────────────────────────────────────── */
/* 改造前进出场**完全一样**（都是 0.2s / -8px）。技能的一条硬规矩是
   「出场比进场短、比进场静」：用户已经看到内容了，退场不该再演一遍完整行程。 */

/* 进场：从上方落下 + 轻微放大 + 一点模糊。
   模糊是关键 —— 它让「从远处聚焦过来」的感觉成立，比单纯位移更像"浮现"。 */
.fc-toast-enter-active {
  transition:
    opacity var(--fc-dur-enter) var(--fc-ease-out),
    transform var(--fc-dur-enter) var(--fc-ease-out),
    filter var(--fc-dur-enter) var(--fc-ease-out);
}
.fc-toast-enter-from {
  opacity: 0;
  transform: translateY(-10px) scale(0.96);
  filter: blur(2px);
}

/* 出场：只淡出 + 极轻地缩一下，位移更小、时间更短 */
.fc-toast-leave-active {
  transition:
    opacity var(--fc-dur-exit) var(--fc-ease-out),
    transform var(--fc-dur-exit) var(--fc-ease-out);
}
.fc-toast-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.98);
}

/* 减少动效偏好下只留淡入淡出，去掉模糊与缩放 */
@media (prefers-reduced-motion: reduce) {
  .fc-toast-enter-from,
  .fc-toast-leave-to {
    transform: none;
    filter: none;
  }
}
</style>
