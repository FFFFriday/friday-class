/**
 * 基础组件统一出口。
 *
 * 用法：
 *   import { FcButton, FcCard, useToast } from '@/components/base'
 *
 * 为什么统一出口而不是在 main.js 里全局注册：
 * 全局注册会让「这个组件从哪来」变得不可追（模板里凭空出现一个 `<FcButton>`，
 * 搜代码搜不到定义），而且无法被 tree-shaking 剔除。
 * 具名导入牺牲一点书写便利，换来的是来源可查。
 *
 * ⚠ 新页面一律用这些组件 + `styles/tokens.css` 的变量，不要另起炉灶写死颜色。
 *   老页面的回填统一在 P5 做。
 */

export { default as FcButton } from './FcButton.vue'
export { default as FcCard } from './FcCard.vue'
export { default as FcModal } from './FcModal.vue'
export { default as FcConfirmHost } from './FcConfirmHost.vue'
export { default as FcInput } from './FcInput.vue'
export { default as FcTable } from './FcTable.vue'
export { default as FcTag } from './FcTag.vue'
export { default as FcBadge } from './FcBadge.vue'
export { default as FcToast } from './FcToast.vue'
export { default as FcSkeleton } from './FcSkeleton.vue'
export { default as FcEmptyState } from './FcEmptyState.vue'
export { default as FcLoading } from './FcLoading.vue'
