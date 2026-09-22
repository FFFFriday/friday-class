import { reactive, readonly } from 'vue'

/**
 * 全局轻提示（Toast）。
 *
 * 设计取舍：用「模块级单例 + 响应式数组」而不是 Pinia store。
 * 提示是纯 UI 状态，不进 localStorage、不需要跨页面持久化、
 * 也没有业务逻辑，为它建一个 store 属于过度设计。
 *
 * 渲染出口只有一处：App.vue 里的 <FcToast />。
 * 正因为是单例，全局同时只有这一处渲染，不会出现多个容器叠着弹。
 */

let seed = 0

const items = reactive([])

/** 默认停留时长（毫秒）。错误类故意留久一点——报错信息需要时间读。 */
const DURATION = {
  success: 2500,
  info: 2500,
  warning: 3500,
  error: 5000,
}

/** 同屏最多几条。超了就顶掉最旧的，避免刷屏式报错糊满屏幕。 */
const MAX_VISIBLE = 4

/**
 * 弹一条提示。
 * @param {string} message 文案（纯文本，按文本插值渲染）
 * @param {'success'|'info'|'warning'|'error'} type
 * @param {number} [duration] 自定义停留毫秒数；传 0 表示不自动关闭
 */
export function showToast(message, type = 'info', duration) {
  const text = String(message ?? '').trim()
  if (!text) {
    return null
  }

  const id = ++seed
  const ms = duration === undefined ? (DURATION[type] ?? 3000) : duration

  const toast = { id, message: text, type, timer: null }
  items.push(toast)

  // 超出上限就摘掉最早的（连带清掉它的定时器，否则那个定时器
  // 还会在将来某一刻去操作一个已经不在列表里的对象）
  while (items.length > MAX_VISIBLE) {
    const dropped = items.shift()
    if (dropped?.timer) {
      clearTimeout(dropped.timer)
    }
  }

  if (ms > 0) {
    // ⚠ 这里是 dismissToast，不是 dismiss。
    // 曾经写成 `dismiss(id)`，而本模块根本没有这个名字的函数（只有 dismissToast，
    // `useToast()` 返回的对象上才叫 dismiss）。后果不是报错退出，而是**回调里抛
    // ReferenceError**，于是每条提示都永远不消失，一路堆到同屏上限为止。
    // 这类「名字差一个字、编译期查不出、只在运行时炸在回调里」的 bug，
    // 靠构建是抓不到的，只能真点一次界面。
    toast.timer = setTimeout(() => dismissToast(id), ms)
  }

  return id
}

/** 关掉一条。手动关闭时必须清定时器，否则会留下悬挂的 timer。 */
export function dismissToast(id) {
  const index = items.findIndex((item) => item.id === id)
  if (index === -1) {
    return
  }
  const [removed] = items.splice(index, 1)
  if (removed?.timer) {
    clearTimeout(removed.timer)
  }
}

/** 全部关掉。路由切换时清场用，避免上一页的报错飘到下一页。 */
export function clearToasts() {
  items.forEach((item) => item.timer && clearTimeout(item.timer))
  items.splice(0, items.length)
}

export function useToast() {
  return {
    toasts: readonly(items),
    show: showToast,
    dismiss: dismissToast,
    clear: clearToasts,
    success: (message, duration) => showToast(message, 'success', duration),
    info: (message, duration) => showToast(message, 'info', duration),
    warning: (message, duration) => showToast(message, 'warning', duration),
    error: (message, duration) => showToast(message, 'error', duration),
  }
}
