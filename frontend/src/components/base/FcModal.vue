<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  title: { type: String, default: '' },
  width: { type: String, default: '480px' },
  /** 点遮罩是否关闭。破坏性确认框应传 false，避免误点关掉又以为已确认。 */
  closeOnMask: { type: Boolean, default: true },
  closeOnEsc: { type: Boolean, default: true },
  /**
   * 打开后多少毫秒自动关闭；0 = 不自动关（默认）。
   *
   * 到点只发 `autoClose` 事件、**不自己关**：超时该解释成什么
   * （取消？确认？）是调用方的语义，组件不替它决定。
   * `FcConfirmHost` 把它解释成「取消」——危险操作的超时默认必须是安全的。
   */
  autoCloseMs: { type: Number, default: 0 },
})

const emit = defineEmits(['autoClose'])

const open = defineModel({ type: Boolean, default: false })

/** 本次打开是否已因超时触发过，避免重复 emit。 */
const autoClosed = ref(false)

let autoCloseTimer = null

function close() {
  open.value = false
}

function clearAutoClose() {
  if (autoCloseTimer !== null) {
    clearTimeout(autoCloseTimer)
    autoCloseTimer = null
  }
}

function startAutoClose() {
  clearAutoClose()
  autoClosed.value = false
  if (props.autoCloseMs > 0) {
    autoCloseTimer = setTimeout(() => {
      autoCloseTimer = null
      autoClosed.value = true
      emit('autoClose')
    }, props.autoCloseMs)
  }
}

function onMaskClick() {
  if (props.closeOnMask) {
    close()
  }
}

function onKeydown(event) {
  if (event.key === 'Escape' && props.closeOnEsc) {
    close()
  }
}

/**
 * 打开时锁滚动、并接管 Esc。
 *
 * 监听器挂在 window 上、打开时才挂：这个组件可能有多个实例，
 * 只在打开期间占用全局事件，关掉就还回去，不会互相抢。
 */
function bind() {
  window.addEventListener('keydown', onKeydown)
  document.body.style.overflow = 'hidden'
  startAutoClose()
}

function unbind() {
  window.removeEventListener('keydown', onKeydown)
  document.body.style.overflow = ''
  // 必须清掉：不清的话弹窗关了计时器还在跑，到点对着已关闭的弹窗再发一次事件，
  // 上层就被多 resolve 一次
  clearAutoClose()
}

watch(open, (value) => (value ? bind() : unbind()), { immediate: true })

onBeforeUnmount(unbind)
</script>

<template>
  <!-- Teleport 到 body：弹窗若留在原位置，父级的 overflow:hidden 或
       transform 会把它裁掉 / 让它定位失准。挂到 body 下最稳。 -->
  <Teleport to="body">
    <div v-if="open" class="fc-modal" role="dialog" aria-modal="true">
      <div class="fc-modal__mask" @click="onMaskClick" />

      <div class="fc-modal__panel" :style="{ width }">
        <header class="fc-modal__header">
          <h3 class="fc-modal__title">{{ title }}</h3>
          <button class="fc-modal__close" type="button" aria-label="关闭" @click="close">×</button>
        </header>

        <div class="fc-modal__body">
          <slot />
        </div>

        <footer v-if="$slots.footer" class="fc-modal__footer">
          <slot name="footer" />
        </footer>

        <!--
          倒计时条：让「它会自己关」这件事可见。
          悄悄自动关闭比不关更让人困惑——用户会以为是自己点错了或没点到。
          用 CSS 动画而不是 JS 每帧更新剩余宽度：动画在合成器上跑，不占主线程。
        -->
        <div
          v-if="autoCloseMs > 0 && !autoClosed"
          class="fc-modal__auto-close"
          :style="{ animationDuration: `${autoCloseMs}ms` }"
          aria-hidden="true"
        />
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.fc-modal {
  position: fixed;
  inset: 0;
  z-index: var(--fc-z-modal);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--fc-space-4);
}

.fc-modal__mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
}

.fc-modal__panel {
  position: relative;
  max-width: 100%;
  max-height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--fc-bg-panel);
  border-radius: var(--fc-radius-lg);
  box-shadow: var(--fc-shadow-lg);
}

.fc-modal__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fc-space-3);
  padding: var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
}

.fc-modal__title {
  font-size: var(--fc-font-md);
  font-weight: var(--fc-weight-semibold);
}

.fc-modal__close {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: var(--fc-radius-sm);
  font-size: 20px;
  line-height: 1;
  color: var(--fc-text-faint);
  transition: background var(--fc-transition), color var(--fc-transition);
}
.fc-modal__close:hover {
  background: var(--fc-bg-muted);
  color: var(--fc-text);
}

/* 内容区可滚动，标题与按钮固定——长内容（如总结正文）不该把按钮顶出屏幕 */
.fc-modal__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: var(--fc-space-4);
}

.fc-modal__footer {
  display: flex;
  justify-content: flex-end;
  gap: var(--fc-space-2);
  padding: var(--fc-space-3) var(--fc-space-4);
  border-top: 1px solid var(--fc-border);
}

/* 贴在面板底部的倒计时条：从满宽缩到 0 */
.fc-modal__auto-close {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  height: 3px;
  border-radius: 0 0 var(--fc-radius-lg) var(--fc-radius-lg);
  background: var(--fc-text-faint);
  opacity: 0.55;
  transform-origin: left center;
  animation-name: fc-modal-countdown;
  animation-timing-function: linear;
  animation-fill-mode: forwards;
}

@keyframes fc-modal-countdown {
  from {
    transform: scaleX(1);
  }
  to {
    transform: scaleX(0);
  }
}

/* 尊重「减少动态效果」偏好：不播放缩放动画，留一条静态提示线 */
@media (prefers-reduced-motion: reduce) {
  .fc-modal__auto-close {
    animation: none;
    opacity: 0.3;
  }
}
</style>
