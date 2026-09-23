import { computed, ref } from 'vue'
import { confirm } from '@/composables/useConfirm'
import { useToast } from '@/composables/useToast'

/**
 * 表格「勾选若干行 → 批量删除」的通用逻辑。三张管理端表格共用。
 *
 * <p><b>为什么逐条调用已有的单条删除接口，而不是新开一个批量删除端点：</b>
 * 删除是破坏性操作。复用**已经被验证过**的删除路径，比新增一条
 * 「一次删一堆」、没人测过的代码安全得多 —— 后者一旦写错，一次就是一批数据。
 * 代价是 N 次请求；管理端一页 20 条，内网下可以接受。
 *
 * <p><b>为什么必须处理部分失败：</b>删到第 7 个时后端报错，前 6 个其实已经删了。
 * 这时说一句笼统的「操作完成」就是在骗人。所以逐条收集失败原因，
 * 最后按「全成功 / 部分成功 / 全失败」三种口径分开提示。
 *
 * @param {object}   opts
 * @param {import('vue').Ref<Array>} opts.rows      当前页的行
 * @param {(row) => any}             opts.idOf      取行主键
 * @param {(row) => string}          [opts.nameOf]  取行的展示名（确认框里列出来）
 * @param {(row) => Promise<any>}    opts.removeOne 单条删除，调用方传自己已有的那个函数体
 * @param {string}                   opts.noun      量词，如「个账号」
 * @param {string}                   [opts.note]    确认框里的补充说明（后果、能否撤销）
 * @param {(row) => boolean}         [opts.canPick] 这一行**能不能**被勾选。默认都能。
 *     账号管理用它禁掉「自己」那一行 —— 后端本来就会拒绝删自己，
 *     但让用户先选上、点了才被拒，体验是差的。
 * @param {() => any}                [opts.onDone]  全部跑完后的收尾（一般是重新加载列表）
 */
export function useBulkDelete({
  rows,
  idOf,
  nameOf,
  removeOne,
  noun,
  note = '',
  canPick,
  onDone,
}) {
  const toast = useToast()

  /** 已勾选的主键集合。用 Set 不用数组：判断「这一行选没选」是 O(1)。 */
  const picked = ref(new Set())
  const removing = ref(false)

  /** 本页**允许**被勾选的行。「全选」只作用于这些行。 */
  const pickableRows = computed(() =>
    canPick ? rows.value.filter((row) => canPick(row)) : rows.value,
  )

  const pickedRows = computed(() => rows.value.filter((row) => picked.value.has(idOf(row))))
  const pickedCount = computed(() => pickedRows.value.length)

  /** 本页是否全选。空列表算作**未**全选 —— 否则「没有数据」会显示成「已全选」。 */
  const allPicked = computed(
    () =>
      pickableRows.value.length > 0 &&
      pickableRows.value.every((row) => picked.value.has(idOf(row))),
  )

  function togglePick(id) {
    const next = new Set(picked.value)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.add(id)
    }
    picked.value = next
  }

  function toggleAll() {
    picked.value = allPicked.value ? new Set() : new Set(pickableRows.value.map(idOf))
  }

  /**
   * 清空勾选。**换页、改筛选之后必须调** ——
   * 否则「已选 3 项」里混着上一页的行，批量删除会删掉用户根本没看见的东西。
   */
  function clearPicked() {
    picked.value = new Set()
  }

  async function removePicked() {
    if (!pickedCount.value || removing.value) {
      return
    }

    const list = pickedRows.value
    const names = nameOf ? list.map(nameOf).filter(Boolean) : []
    const preview = names.slice(0, 5).join('、')
    const rest = names.length > 5 ? ` 等 ${names.length} 项` : ''

    const ok = await confirm({
      title: `删除 ${list.length} ${noun}`,
      message: `即将删除：${preview}${rest}。\n\n${note}`,
      confirmText: '删除',
      danger: true,
    })
    if (!ok) {
      return
    }

    removing.value = true
    const failed = []
    // 串行而不是 Promise.all：并发删同一批数据更容易撞后端约束，
    // 而且串行时「删到第几个失败」的语义是清楚的。
    for (const row of list) {
      try {
        await removeOne(row)
      } catch (e) {
        failed.push(e.message || '未知错误')
      }
    }
    removing.value = false
    clearPicked()

    const done = list.length - failed.length
    if (!failed.length) {
      toast.success(`已删除 ${list.length} ${noun}`)
    } else if (done === 0) {
      toast.error(`${list.length} ${noun}全部删除失败：${failed[0]}`)
    } else {
      toast.warning(`已删除 ${done} ${noun}，${failed.length} 个失败：${failed[0]}`)
    }

    await onDone?.()
  }

  return {
    picked,
    pickedRows,
    pickedCount,
    allPicked,
    removing,
    togglePick,
    toggleAll,
    clearPicked,
    removePicked,
  }
}
