// src/composables/useClassChat.js
//
// 课堂讨论区的状态与收发（M2）。
//
// 【为什么不做「乐观渲染」】
// 发出去的发言，服务端会**广播给全课堂（含发送者自己）**。
// 如果前端先本地插一条、再收到广播，就会同一条显示两遍；
// 要做对就得给每条消息编客户端 id 再比对去重——为一个本地回环 50ms 的
// 场景引入一整套去重逻辑，不划算。
// 所以这里**只信服务端广播**：发出去后不回显，等广播回来统一进列表。
// 好处是「列表里的每一条都确实在数据库里」，刷新前后完全一致。

import { computed, onUnmounted, ref } from 'vue'
import http from '@/api/http'

/** 与后端 ChatService.MIN_SEND_INTERVAL_MS（2 秒）对齐，多给 0.5 秒吸收时钟误差。 */
const RATE_LIMIT_COOLDOWN_MS = 2500

/** 单条上限，与 chat_message.content 的列长度一致。 */
export const MAX_CONTENT_LENGTH = 1000

export function useClassChat(socket, sessionId) {
  const messages = ref([])
  const loading = ref(false)
  const loadError = ref('')
  const sendError = ref('')
  const hasMore = ref(false)
  const loadingMore = ref(false)

  /**
   * 限流冷却倒计时（秒）。
   *
   * 为什么要有它：429 不是「出错了」，是「等两秒就好」。
   * 只弹一句红字的话，学生不知道要等多久，会反复点、反复收到同一句话；
   * 把剩余秒数写在按钮上，他看一眼就知道该等。
   */
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

  /**
   * 拉历史。三种用法：
   *   - 不传参          → 首次进入，取最近 N 条
   *   - `{ afterId }`   → 断线补拉，取比它更新的
   *   - `{ beforeId }`  → 「加载更早」，取比它更早的一页
   *
   * 三条路径都按 id 去重后再合并：断线期间广播可能已经补了一部分，
   * 不去重会出现同一条显示两遍。
   */
  async function load({ afterId = null, beforeId = null, limit } = {}) {
    const isFirst = !afterId && !beforeId
    const isOlder = Boolean(beforeId)

    if (isFirst) loading.value = true
    else loadingMore.value = true
    loadError.value = ''

    try {
      const params = {}
      if (afterId) params.afterId = afterId
      if (beforeId) params.beforeId = beforeId
      if (limit) params.limit = limit

      const data = await http.get(`/chat/${sessionId}/messages`, { params })
      const items = data.items || []
      const known = new Set(messages.value.map((m) => m.id))
      const fresh = items.filter((m) => !known.has(m.id))

      if (isFirst) {
        messages.value = items
        hasMore.value = data.hasMore
      } else if (isOlder) {
        // 更早的插到列表**前面**，顺序才是「从旧到新」
        if (fresh.length) messages.value = [...fresh, ...messages.value]
        hasMore.value = data.hasMore
      } else if (fresh.length) {
        messages.value = [...messages.value, ...fresh]
      }
    } catch (e) {
      loadError.value = e.message || '讨论区暂时读不到'
    } finally {
      loading.value = false
      loadingMore.value = false
    }
  }

  /** 「加载更早」：拿当前最早那条的 id 当游标。 */
  function loadOlder() {
    if (!messages.value.length || loadingMore.value) return
    load({ beforeId: messages.value[0].id })
  }

  /** 追加一条服务端广播来的新发言。按 id 去重，防重连时重复推送。 */
  function appendMessage(msg) {
    if (messages.value.some((m) => m.id === msg.id)) return
    messages.value = [
      ...messages.value,
      {
        id: msg.id,
        userId: msg.userId,
        nickname: msg.nickname,
        role: msg.role,
        content: msg.content,
        pageId: msg.pageId ?? null,
        pageNo: msg.pageNo ?? null,
        status: 'NORMAL',
        createdAt: msg.createdAt,
      },
    ]
  }

  /** 把某条标为已撤回。**同时清掉正文**——服务端的历史接口也不再返回它。 */
  function markDeleted(id) {
    messages.value = messages.value.map((m) =>
      m.id === id ? { ...m, status: 'DELETED', content: null } : m,
    )
  }

  /** 发一条发言。 */
  function send(content, pageId) {
    const text = String(content ?? '').trim()
    if (!text || cooldownLeft.value > 0) return false

    sendError.value = ''

    // clientMsgId：网络重发或手抖连点时，服务端靠它把重复的吞掉
    const clientMsgId =
      globalThis.crypto?.randomUUID?.() ??
      `m-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`

    const ok = socket.send({
      type: 'chat.send',
      content: text.slice(0, MAX_CONTENT_LENGTH),
      clientMsgId,
      pageId: pageId ?? null,
    })

    if (!ok) {
      sendError.value = '实时通道未连接，发言没发出去'
    }
    return ok
  }

  /** 撤回一条（老师/管理员）。走 REST，因为它是低频且不能丢的操作。 */
  async function remove(messageId) {
    sendError.value = ''
    try {
      await http.delete(`/chat/messages/${messageId}`)
      // 不在这里改本地状态：等 chat.deleted 广播回来统一处理，
      // 保证「自己看到的」和「别人看到的」一致
    } catch (e) {
      sendError.value = e.message || '撤回失败'
    }
  }

  // ── 订阅服务端事件 ─────────────────────────────────────

  const offs = [
    socket.on('chat.new', appendMessage),
    socket.on('chat.deleted', (msg) => markDeleted(msg.id)),
    socket.on('error', (msg) => {
      // 限流不当错误处理：它是「稍等」，用按钮倒计时表达更清楚
      if (msg.code === 429) {
        startCooldown()
      } else {
        sendError.value = msg.message || '操作失败'
      }
    }),
  ]

  onUnmounted(() => offs.forEach((off) => off()))

  const isEmpty = computed(() => !loading.value && messages.value.length === 0)

  return {
    messages,
    loading,
    loadError,
    sendError,
    hasMore,
    loadingMore,
    cooldownLeft,
    isEmpty,
    load,
    loadOlder,
    send,
    remove,
    startCooldown,
  }
}
