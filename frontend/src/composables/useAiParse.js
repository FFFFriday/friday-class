// src/composables/useAiParse.js
// AI 解析（F002）的状态 + 轮询。教师端的「课件详情页」与「直播控制台」共用。
//
// 为什么要抽出来：两处都要「触发 → 轮询进度 → 收尾刷新」，而轮询是那种
// 写两遍就一定会漏掉一份 clearInterval 的代码 —— 漏掉的表现是组件卸载后
// 还在打接口，切来切去的学生/老师页面上就会看到一堆莫名其妙的请求。
//
// 后端的约定（见 CoursewareAiController）：
//   POST /api/courseware/{id}/parse            → 立即返回当前进度，解析在后台跑
//   GET  /api/courseware/{id}/parse-progress   → 轮询用，status 到终态就停止轮询
import { computed, onUnmounted, ref } from 'vue'
import http from '@/api/http'

/**
 * 轮询间隔。后端每写完一页才推进一次进度，而单页要调一次模型（1.5~3 秒），
 * 所以 2.5 秒既不会漏掉推进，也不会把接口打爆。
 */
const POLL_INTERVAL_MS = 2500

/** 任务还没结束的两个状态。终态是 SUCCESS / PARTIAL / FAILED。 */
function isRunning(status) {
  return status === 'PENDING' || status === 'RUNNING'
}

/**
 * @param getCoursewareId 返回课件 ID 的函数。用函数而不是值，因为两处都拿不到现成的：
 *        直播控制台要等 `/session/{id}` 返回后才知道 coursewareId；
 *        课件详情页则随路由参数变化（同一个组件实例会换课件）。
 * @param onSettled 解析收尾（成功 / 部分失败 / 失败）时回调。
 *        用来刷新「解析的出产物」——知识点、课件状态。轮询自己不该知道要刷什么。
 */
export function useAiParse(getCoursewareId, { onSettled } = {}) {
  /** ParseProgressResponse 原样存着，页面按需取字段。未查到过时为 null。 */
  const progress = ref(null)
  /** 是否已经成功查过一次进度。用来区分「还没查完」和「查完了但没解析过」。 */
  const loaded = ref(false)
  const loading = ref(false)
  const triggering = ref(false)
  const error = ref('')

  let timer = null

  const status = computed(() => progress.value?.status ?? null)
  const running = computed(() => isRunning(status.value))
  const percent = computed(() => progress.value?.progress ?? 0)
  const donePages = computed(() => progress.value?.currentPage ?? 0)
  const totalPages = computed(() => progress.value?.totalPages ?? null)
  /** 有没有解析过。`status` 为 null 表示从未有过解析任务。 */
  const everParsed = computed(() => status.value !== null)

  /**
   * 老师需要留意：从未解析、解析失败、部分页失败。
   *
   * ⚠️ `loaded` 这个条件不能省：首次查询返回之前 `status` 也是 null，
   * 省掉的话页面会在「还没查完」的一瞬间先闪一下「尚未解析」。
   */
  const needsAttention = computed(
    () => loaded.value && !running.value && status.value !== 'SUCCESS',
  )

  const statusLabel = computed(() => {
    if (!loaded.value) return ''
    switch (status.value) {
      case null:
        return '尚未解析'
      case 'PENDING':
        return '排队中'
      case 'RUNNING':
        return `解析中 ${percent.value}%`
      case 'SUCCESS':
        return '解析完成'
      case 'PARTIAL':
        return '解析完成（有页面失败）'
      case 'FAILED':
        return '解析失败'
      default:
        return ''
    }
  })

  /** 一句给老师看的人话：现在什么情况、要不要做点什么。 */
  const hint = computed(() => {
    if (!loaded.value) return ''
    if (running.value) {
      return `已完成 ${donePages.value}${totalPages.value ? ` / ${totalPages.value}` : ''} 页…`
    }
    if (status.value === 'SUCCESS') {
      return totalPages.value
        ? `全部 ${totalPages.value} 页解析完成。学生提问时，AI 会按当前页的知识点回答。`
        : '解析完成。学生提问时，AI 会按当前页的知识点回答。'
    }
    // PARTIAL / FAILED 都带 errorMessage
    if (progress.value?.errorMessage) return progress.value.errorMessage
    return '这份课件还没有解析过。不解析的话，学生提问时 AI 助手拿不到本页知识点，只会回一句「本页暂无内容」。'
  })

  async function fetchProgress(silent) {
    const id = getCoursewareId()
    if (!id) return null
    if (!silent) loading.value = true
    try {
      const data = await http.get(`/courseware/${id}/parse-progress`)
      progress.value = data
      loaded.value = true
      error.value = ''
      return data
    } catch (e) {
      // 轮询期间的网络抖动不该把界面打回错误态：下一次 tick 还能补上，
      // 中途弹个红字只会让老师以为解析坏了。
      if (!silent) error.value = e.message || '读取解析进度失败'
      return null
    } finally {
      if (!silent) loading.value = false
    }
  }

  /**
   * 查一次进度；如果发现正在解析，顺手把轮询接上。
   *
   * 这条「顺手接上」很重要：老师刷新页面（或换设备进来）时，解析其实还在后台跑，
   * 不接上的话进度条就永远停在最初那一下，看起来像卡死了。
   */
  async function refresh(silent = false) {
    const data = await fetchProgress(silent)
    if (data && isRunning(data.status)) startPolling()
    return data
  }

  function startPolling() {
    if (timer) return
    timer = setInterval(tick, POLL_INTERVAL_MS)
  }

  async function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  async function tick() {
    const data = await fetchProgress(true)
    // 查不到（网络抖动）就继续轮询；查到了且还是进行中，也继续
    if (!data || isRunning(data.status)) return
    await stopPolling()
    if (onSettled) await onSettled(data)
  }

  /**
   * 触发解析。返回是否触发成功。
   *
   * 后端对「同一课件重复触发」是拒绝的（返回「该课件正在解析中」）。
   * 但那句话其实说明**解析正在跑**，所以这里不把它当成失败甩给老师，
   * 而是顺势把轮询接上 —— 否则老师会以为按钮坏了。
   */
  async function trigger() {
    const id = getCoursewareId()
    if (!id || triggering.value) return false

    triggering.value = true
    error.value = ''
    try {
      progress.value = await http.post(`/courseware/${id}/parse`)
      loaded.value = true
      startPolling()
      return true
    } catch (e) {
      error.value = e.message || '触发解析失败'
      const data = await fetchProgress(true)
      if (data && isRunning(data.status)) startPolling()
      return false
    } finally {
      triggering.value = false
    }
  }

  onUnmounted(stopPolling)

  return {
    progress,
    loaded,
    loading,
    triggering,
    error,
    status,
    running,
    percent,
    donePages,
    totalPages,
    everParsed,
    needsAttention,
    statusLabel,
    hint,
    refresh,
    trigger,
    stopPolling,
  }
}
