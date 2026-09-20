// src/composables/useChatHistory.js
//
// 单个 AI 会话的消息加载与发送（M4）。
//
// 【提问走的仍然是 POST /api/qa/ask】
// 只是多带了一个可选的 conversationId。刻意**不新建第二个提问接口**——
// 否则限流、幂等、计费这三套逻辑要在两条路径上各维护一份，迟早分叉。

import { onUnmounted, ref } from 'vue'
import http from '@/api/http'

/** 与后端 QaService.MIN_ASK_INTERVAL_MS（5 秒）对齐，多给 0.5 秒吸收时钟误差。 */
const RATE_LIMIT_COOLDOWN_MS = 5500

/** 后端业务码 429。见 api/http.js 里 bizCode 的来源。 */
const BIZ_RATE_LIMITED = 429

export function useChatHistory() {
  const messages = ref([])
  const loading = ref(false)
  const loadError = ref('')
  const askError = ref('')
  const sending = ref(false)

  /** 没调模型、也没落库的临时提示（课件解析中 / 本页无内容）。只当提示显示。 */
  const notice = ref('')

  const cooldownLeft = ref(0)
  let cooldownUntil = 0
  let cooldownTimer = null

  function tickCooldown() {
    const left = cooldownUntil - Date.now()
    cooldownLeft.value = left > 0 ? Math.ceil(left / 1000) : 0
    if (left <= 0) stopCooldown()
  }

  function startCooldown(ms = RATE_LIMIT_COOLDOWN_MS) {
    cooldownUntil = Date.now() + ms
    tickCooldown()
    if (!cooldownTimer) cooldownTimer = setInterval(tickCooldown, 250)
  }

  function stopCooldown() {
    if (cooldownTimer) {
      clearInterval(cooldownTimer)
      cooldownTimer = null
    }
    cooldownUntil = 0
    cooldownLeft.value = 0
  }

  onUnmounted(stopCooldown)

  /** 载入某会话的全部消息。接口一次给完（单会话有 200 条上限），不做分页。 */
  async function load(conversationId) {
    if (!conversationId) {
      messages.value = []
      return
    }
    loading.value = true
    loadError.value = ''
    notice.value = ''
    stopCooldown()
    try {
      const data = await http.get(`/ai/conversations/${conversationId}`)
      messages.value = data.messages || []
    } catch (e) {
      loadError.value = e.message || '消息加载失败'
      messages.value = []
    } finally {
      loading.value = false
    }
  }

  function reset() {
    messages.value = []
    loadError.value = ''
    askError.value = ''
    notice.value = ''
    stopCooldown()
  }

  /**
   * 幂等键：同一次「提问意图」在重试时必须用同一个键。
   *
   * 后端按 client_request_id 去重，键一样就**返回上次那条记录**，
   * 不重复调模型、也不多写一行。这是「请求超时了，但服务端其实已经答完」
   * 那种情况下最省钱的一道保险。保留它（不省钱的是答案缓存，那个已删）。
   *
   * ⚠️ 键必须跟着**会话 + 问题**走。只存一个键的话，学生在会话 A 提问超时、
   * 换个会话再问别的，复用旧键就会拿到 A 的回答——答非所问，且完全看不出哪里错了。
   */
  let pending = { key: null, conversationId: null, text: '' }

  async function ask(question, { conversationId, pageId = null, sessionId = null } = {}) {
    const text = String(question ?? '').trim()
    if (!text || sending.value || cooldownLeft.value > 0) return null

    if (pending.conversationId !== conversationId || pending.text !== text) {
      pending = {
        key:
          globalThis.crypto?.randomUUID?.() ??
          `req-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`,
        conversationId,
        text,
      }
    }

    sending.value = true
    askError.value = ''
    notice.value = ''
    try {
      const data = await http.post('/qa/ask', {
        sessionId,
        pageId,
        conversationId,
        question: text,
        clientRequestId: pending.key,
      })

      if (data.status === 'SKIPPED') {
        // 没调模型、也没落库（id 为 null）。当临时提示显示，**不要**塞进列表：
        // 刷新页面它会消失，看起来像「记录丢了」，而它本来就不在数据库里。
        notice.value = data.answer
      } else {
        messages.value = [...messages.value, { ...data, turn: data.turn ?? messages.value.length + 1 }]
      }

      pending = { key: null, conversationId: null, text: '' }
      return data
    } catch (e) {
      // 出错时**故意保留** pending：再点一次会复用同一个键，
      // 「超时但其实已落库」的情况下就不会重复计费
      if (e.bizCode === BIZ_RATE_LIMITED) {
        // 限流不是错误，是「稍等」——用按钮倒计时表达，不占红字报错区
        startCooldown()
      } else {
        askError.value = e.message || '发送失败'
      }
      return null
    } finally {
      sending.value = false
    }
  }

  return {
    messages,
    loading,
    loadError,
    askError,
    notice,
    sending,
    cooldownLeft,
    load,
    reset,
    ask,
    startCooldown,
  }
}
