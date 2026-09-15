// src/composables/usePromptPack.js
// 提示词包（GET /api/courseware/{id}/prompt-pack）：一份课件的全部页
// 「知识点 + 预置提问」，一次拉全，之后翻页不用再查库。
//
// 这是「AI 按页码定位上下文」能成立的数据前提：
// 学生端靠它拿到当前页的预置提问做快捷提问；教师端靠它看 AI 到底解析出了什么。
// （课件页列表接口 /courseware/{id}/pages 只返回页号和文字，不含知识点 —— 别拿它顶替。）
import { computed, onUnmounted, ref } from 'vue'
import http from '@/api/http'

/** 「解析中 → 跑完了就重拉」的探测间隔。用进度接口探，很轻。 */
const WATCH_INTERVAL_MS = 5000

/**
 * 最多探多少轮。按 5 秒一轮算 ≈ 5 分钟。
 * 解析一份百页课件实测约 100 秒，留足余量；但也不能无限探下去 ——
 * 学生可能开着页面就走了，而解析可能因为失败根本不会结束。
 */
const MAX_WATCH_ROUNDS = 60

/**
 * @param getCoursewareId 返回课件 ID 的函数（理由同 useAiParse：路由参数 / 课堂加载后才拿到）
 * @param watchParsing 解析还在跑时，自动探测并在跑完后重拉一次。
 *        学生端要开（学生可能在老师刚点完解析就进课堂，那时包是空的，
 *        不重拉就会一直空着）；教师端详情页不需要 —— 它自己有解析轮询，收尾时会主动重拉。
 */
export function usePromptPack(getCoursewareId, { watchParsing = false } = {}) {
  const pack = ref(null)
  const loading = ref(false)
  const error = ref('')

  let timer = null
  let rounds = 0

  /**
   * pageId → PagePack 的索引。
   *
   * 🔴 键必须是 pageId，**不能用 pageNo**：两份课件的「第 1 页」是两个不同的 pageId，
   * 用 pageNo 当键会互相覆盖，导致引用到**错误页**的知识点。
   * 这种错是静默的 —— 不报错、不白屏，只是答非所问，极难排查。
   */
  const byPageId = computed(() => {
    const map = Object.create(null)
    for (const p of pack.value?.pages || []) {
      map[p.pageId] = p
    }
    return map
  })

  /** 取某一页的知识点/预置提问。拿不到返回 null（还没解析、或页不存在）。 */
  function pagePack(pageId) {
    return pageId == null ? null : byPageId.value[pageId] || null
  }

  /** 课件当前状态。用来区分「还在解析」与「本页确实没内容」。 */
  const parseStatus = computed(() => pack.value?.parseStatus ?? null)
  const parsed = computed(() => pack.value?.parseVersion > 0)

  async function load() {
    const id = getCoursewareId()
    if (!id) return
    loading.value = true
    try {
      pack.value = await http.get(`/courseware/${id}/prompt-pack`)
      error.value = ''
      watchIfParsing()
    } catch (e) {
      error.value = e.message || '读取知识点失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 「进课堂时解析还没跑完 → 缓存下来是空的 → 于是永远为空」。
   *
   * 这正是接口里带 `parseVersion` 要解决的问题，但客户端光有版本号没用 ——
   * 它得先知道版本变了。所以解析中用进度接口（很轻，不带知识点）探一探，
   * 跑完了再拉一次提示词包。
   */
  function watchIfParsing() {
    if (!watchParsing || timer) return
    if (parseStatus.value !== 'PARSING') return

    rounds = 0
    timer = setInterval(async () => {
      rounds += 1
      if (rounds > MAX_WATCH_ROUNDS) {
        stopWatching()
        return
      }
      const id = getCoursewareId()
      if (!id) {
        stopWatching()
        return
      }
      try {
        const p = await http.get(`/courseware/${id}/parse-progress`)
        if (p?.status !== 'PENDING' && p?.status !== 'RUNNING') {
          await stopWatching()
          await load()
        }
      } catch {
        // 抖动忽略，下一轮再试
      }
    }, WATCH_INTERVAL_MS)
  }

  async function stopWatching() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  onUnmounted(stopWatching)

  return { pack, loading, error, byPageId, pagePack, parseStatus, parsed, load, stopWatching }
}
