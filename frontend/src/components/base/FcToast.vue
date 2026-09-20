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
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: var(--fc-radius-sm);
  font-size: 16px;
  line-height: 1;
  color: var(--fc-text-faint);
}
.fc-toast__close:hover {
  background: var(--fc-bg-muted);
  color: var(--fc-text);
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

.fc-toast-enter-active,
.fc-toast-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}
.fc-toast-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}
.fc-toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
