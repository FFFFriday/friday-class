<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import FcModal from './FcModal.vue'
import FcButton from './FcButton.vue'
import FcInput from './FcInput.vue'
import { accept, autoDismiss, confirmState, dismiss } from '@/composables/useConfirm'

/**
 * 全局确认 / 输入弹窗的唯一宿主。在 `App.vue` 里挂一次即可。
 *
 * 只做三件事：把 {@link confirmState} 渲染出来、把按钮点击转成
 * `accept()` / `dismiss()` / `autoDismiss()`、管焦点。
 * 所有业务规则都在 `composables/useConfirm.js` 里，这里不判断任何一条。
 */

const inputWrapEl = ref(null)
const footerEl = ref(null)

/**
 * 把 `FcModal` 的双向绑定接到全局状态上。
 *
 * 组件的 × / Esc / 点遮罩都会走它自己的 `close()` → 置 false → 触发这里的 setter。
 * 于是「关掉」只有一个出口，不会出现「弹窗关了但 promise 还悬着」。
 */
const open = computed({
  get: () => confirmState.open,
  set: (value) => {
    if (!value && confirmState.open) {
      dismiss(confirmState.kind)
    }
  },
})

/** 危险操作把确定按钮标红；输入框的确定永远不标红（它不是破坏性动作）。 */
const confirmVariant = computed(() => (confirmState.danger ? 'danger' : 'primary'))

const confirmLabel = computed(() => confirmState.confirmText || '确定')
const cancelLabel = computed(() => confirmState.cancelText || '取消')

const isPrompt = computed(() => confirmState.kind === 'prompt')

/**
 * 打开后把焦点放对地方，键盘用户才不用先 Tab 一圈。
 *
 * - 输入框：焦点落到输入框（用户就是来打字的）；
 * - 确认框：焦点落到「**取消**」而不是「确定」——
 *   回车不该顺手把用户删了。
 *
 * 用包裹元素 + querySelector 而不是子组件的 `$el`：
 * `<script setup>` 组件默认是「关闭」的，靠 `$el` 拿 DOM 属于依赖实现细节。
 */
watch(
  () => confirmState.open,
  async (value) => {
    if (!value) {
      // 关掉时把上次的焦点痕迹清干净，下次打开重新算
      return
    }
    await nextTick()
    if (isPrompt.value) {
      inputWrapEl.value?.querySelector('input, textarea')?.focus()
    } else {
      // 页脚里第一个按钮就是「取消」
      footerEl.value?.querySelector('button')?.focus()
    }
  },
)
</script>

<template>
  <FcModal
    v-model="open"
    :title="confirmState.title"
    :width="isPrompt ? '460px' : '440px'"
    :auto-close-ms="confirmState.autoCloseMs"
    @auto-close="autoDismiss"
  >
    <!-- pre-line：调用方文案里有 \n 换行，不能让它塌成一行 -->
    <p v-if="confirmState.message" class="confirm__message">{{ confirmState.message }}</p>

    <div v-if="isPrompt" ref="inputWrapEl" class="confirm__input">
      <FcInput
        v-model="confirmState.inputValue"
        :label="confirmState.inputLabel"
        :placeholder="confirmState.inputPlaceholder"
        :error="confirmState.inputError"
      />
    </div>

    <template #footer>
      <!-- display:contents 让这层包裹对布局完全透明：按钮仍是 FcModal 页脚的直接 flex 子项 -->
      <div ref="footerEl" class="confirm__footer">
        <FcButton variant="secondary" @click="dismiss(confirmState.kind)">{{ cancelLabel }}</FcButton>
        <FcButton :variant="confirmVariant" @click="accept">{{ confirmLabel }}</FcButton>
      </div>
    </template>
  </FcModal>
</template>

<style scoped>
.confirm__message {
  color: var(--fc-text-muted);
  line-height: 1.7;
  /* 文案里的 \n 要真的换行 */
  white-space: pre-line;
}

.confirm__input {
  margin-top: var(--fc-space-3);
}

.confirm__footer {
  display: contents;
}
</style>
