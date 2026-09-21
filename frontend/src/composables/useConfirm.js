import { reactive } from 'vue'

/**
 * 全局确认 / 输入弹窗。
 *
 * <h3>为什么要把 window.confirm 换掉</h3>
 *
 * 1. **原生弹窗不能自动关闭**。这是浏览器的硬限制——`window.confirm` 一旦弹出，
 *    只能由用户点按钮，没有任何 API 能让它超时消失。所以「过一会自己关」
 *    这个需求<b>在原生的基础上做不到</b>，必须换成自绘弹窗。
 * 2. **Chrome 会静默吞掉它**。用户勾了「阻止此页面创建更多对话框」之后，
 *    `window.confirm` 会**立刻返回 false 且不显示任何东西**——点击「删除」看起来毫无反应，
 *    实际是「悄悄取消」了。这类 bug 极难排查，因为它不留任何痕迹。
 * 3. 样式不可控、无法标红危险按钮、移动端体验差。
 *
 * <h3>规矩（对应 Friday 的决策 1）</h3>
 *
 * - **确认框**：到点自动关闭，**超时一律按「取消」处理**（危险操作的默认必须是安全的）；
 *   同时支持 × / Esc / 点遮罩 / 「取消」按钮四种手动关闭。
 * - **输入框**：**不自动关闭**。用户可能正在打字（比如写移出理由、设新密码），
 *   到点把输入内容吞掉比不自动关更讨厌。这条是刻意的例外，不是漏做。
 *
 * <h3>用法</h3>
 * ```js
 * import { confirm, prompt } from '@/composables/useConfirm'
 *
 * if (!(await confirm({ title: '删除用户', message: '……', danger: true }))) return
 *
 * const reason = await prompt({ title: '移出课堂', message: '理由：', defaultValue: '管理员移出课堂' })
 * if (reason === null) return   // 用户取消
 * ```
 *
 * 需要在 `App.vue` 里挂一个 `<FcConfirmHost />` 作为唯一出口。
 */

/** 确认框默认自动关闭时长。够看完两行说明，又不至于让人等。 */
const DEFAULT_AUTO_CLOSE_MS = 12000

/**
 * 是否为「危险操作」（删除、下课这类不可逆动作）。
 * 只影响按钮配色与默认焦点，不影响返回语义。
 */
const state = reactive({
  open: false,
  /** 'confirm' | 'prompt' */
  kind: 'confirm',
  title: '',
  message: '',
  confirmText: '确定',
  cancelText: '取消',
  danger: false,
  /** 0 = 不自动关闭 */
  autoCloseMs: DEFAULT_AUTO_CLOSE_MS,

  // ── prompt 专用 ──
  inputLabel: '',
  inputPlaceholder: '',
  inputValue: '',
  inputError: '',
  /** 输入内容的最短长度。用于「新密码至少 6 位」这类校验。 */
  minLength: 0,
})

/**
 * 当前挂起的 promise 的 resolve。
 * 同一时刻只允许一个弹窗，所以只存一个就够。
 */
let pendingResolve = null

/**
 * 收尾：关掉弹窗并把 promise 兑现。
 *
 * **必须先取出再置空**：`resolve` 会同步唤醒调用方的后续代码，
 * 如果那时 `pendingResolve` 还指着旧的函数，就可能被重复兑现。
 */
function settle(result) {
  const resolve = pendingResolve
  pendingResolve = null
  state.open = false
  if (resolve) {
    resolve(result)
  }
}

/**
 * 关闭当前弹窗。`FcConfirmHost` 在点 × / Esc / 遮罩 / 取消时调用。
 *
 * @param {'confirm'|'prompt'} kind 当前弹窗类型，决定取消时返回 false 还是 null
 */
export function dismiss(kind) {
  settle(kind === 'prompt' ? null : false)
}

/** 点「确定」。prompt 会先跑一遍长度校验，不合格就把弹窗留住并标红。 */
export function accept() {
  if (state.kind === 'prompt') {
    const value = (state.inputValue ?? '').trim()
    if (state.minLength > 0 && value.length < state.minLength) {
      state.inputError = `至少需要 ${state.minLength} 个字符`
      return
    }
    settle(value)
    return
  }
  settle(true)
}

/** 超时自动关闭。确认框 = 取消；输入框不会走到这里（autoCloseMs 为 0）。 */
export function autoDismiss() {
  settle(state.kind === 'prompt' ? null : false)
}

/** 把 state 重置成一次干净的开场白，避免上一次的残留字段漏进来。 */
function reset() {
  state.kind = 'confirm'
  state.title = ''
  state.message = ''
  state.confirmText = '确定'
  state.cancelText = '取消'
  state.danger = false
  state.autoCloseMs = DEFAULT_AUTO_CLOSE_MS
  state.inputLabel = ''
  state.inputPlaceholder = ''
  state.inputValue = ''
  state.inputError = ''
  state.minLength = 0
}

/** 开一个弹窗，返回在它被解决时兑现的 promise。 */
function show(kind, options, fallbackResult) {
  // 上一个还没关就又弹一个：先把前一个按「取消」兑现掉。
  // 不这么做的话它的 promise 永远不 resolve，调用方的 await 会一直悬着——
  // 「删了 A 又立刻点删 B」就能造出来。
  if (pendingResolve) {
    settle(fallbackResult)
  }

  reset()
  state.kind = kind
  state.title = options.title ?? ''
  state.message = options.message ?? ''
  state.confirmText = options.confirmText ?? '确定'
  state.cancelText = options.cancelText ?? '取消'
  state.danger = options.danger ?? false
  state.open = true

  return new Promise((resolve) => {
    pendingResolve = resolve
  })
}

/**
 * 确认框。
 *
 * @param {object} options
 * @param {string} options.title    标题，如「删除用户」
 * @param {string} options.message  正文，可含 \n
 * @param {boolean} [options.danger] 危险操作：确定按钮标红
 * @param {string} [options.confirmText]
 * @param {number} [options.autoCloseMs] 自动关闭毫秒数；传 0 表示不自动关
 * @returns {Promise<boolean>} 点确定 = true；取消 / 关闭 / 超时 = false
 */
export function confirm(options = {}) {
  const promise = show('confirm', options, false)
  state.autoCloseMs = options.autoCloseMs ?? DEFAULT_AUTO_CLOSE_MS
  return promise
}

/**
 * 输入框。**不自动关闭**（理由见文件头）。
 *
 * @param {object} options
 * @param {string} options.title
 * @param {string} [options.message]
 * @param {string} [options.inputLabel]
 * @param {string} [options.placeholder]
 * @param {string} [options.defaultValue]
 * @param {number} [options.minLength] 最短长度，不足时留在弹窗里并标红
 * @param {string} [options.confirmText]
 * @returns {Promise<string|null>} 确定 = 去掉首尾空格后的输入；取消 / 关闭 = null
 */
export function prompt(options = {}) {
  const promise = show('prompt', options, null)
  state.autoCloseMs = 0
  state.inputLabel = options.inputLabel ?? ''
  state.inputPlaceholder = options.placeholder ?? ''
  state.inputValue = options.defaultValue ?? ''
  state.minLength = options.minLength ?? 0
  return promise
}

/** 供 `FcConfirmHost` 读取状态。组件里不解构，保持响应性。 */
export const confirmState = state
