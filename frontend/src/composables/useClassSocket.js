// src/composables/useClassSocket.js
//
// 课堂 WebSocket 的统一封装（协议 v2）。
//
// 它取代了原来的 usePageSync —— 那个只认 `page` / `ended` 两种消息，太窄。
// 升级后同一条连接上还要跑：讨论区（chat.*）、在线名单（presence.*）、
// 屏幕共享信令（webrtc.* / stream.*）、管理端事件（class.paused / kicked）。
//
// 【为什么只开一条连接】
// 每个课堂只建一条 WebSocket，由页面创建一次、显式传给其它 composable
// （useClassChat / useScreenShare / useScreenViewer）。
// 刻意**不做成"按 sessionId 全局缓存单例"**：那样谁在哪连的、谁负责断开，
// 都会变得不可追，组件卸载时很容易漏掉断开、越积越多。
//
// ⚠️ 鉴权：浏览器原生 WebSocket **不能设置请求头**，所以 REST 那套
// `Authorization: Bearer` 用不了。改成「连上后第一帧发 token」。

import { onUnmounted, ref } from 'vue'

/** 重连退避：1s → 2s → 4s → 8s … 上限 30s，避免服务端一挂就被全员同时重连打爆。 */
const BACKOFF_STEPS_MS = [1000, 2000, 4000, 8000, 16000, 30000]

/** 心跳间隔。太短浪费，太长会被中间的代理/网关当成空闲连接掐掉。 */
const HEARTBEAT_MS = 25000

/**
 * @param {string|number} sessionId 课堂 ID
 * @returns 见文件末尾的 return，核心是：
 *   - 响应式状态：connected / ended / paused / onlineCount / selfId / selfRole / lastError
 *   - on(type, fn)：订阅某一类下行消息，返回取消订阅的函数
 *   - send(msg)：发一条上行消息
 */
export function useClassSocket(sessionId) {
  const connected = ref(false)
  /** 服务端广播了下课。一旦为 true 就不再回退——课结束了不会「重新开始」。 */
  const ended = ref(false)
  /** 管理端暂停了本课堂。 */
  const paused = ref(false)
  /** 在线人数（服务端按用户去重，同一个人开两个标签页只算一个）。 */
  const onlineCount = ref(0)
  /** 自己是谁。auth_ok 给的，用来把自己从名单里过滤掉。 */
  const selfId = ref(null)
  const selfRole = ref(null)
  /** 最近一次错误提示。业务提示（如限流）也会走这里，前端可按 code 细看。 */
  const lastError = ref('')
  /** 最近一次错误的业务码。429 = 限流（「慢一点」），不是真出错。 */
  const lastErrorCode = ref(null)

  /** type → Set<handler>。用 Set 而不是数组：重复注册同一函数能天然去重。 */
  const listeners = new Map()

  let socket = null
  let heartbeatTimer = null
  let reconnectTimer = null
  let attempt = 0
  let manuallyClosed = false

  function on(type, handler) {
    if (!listeners.has(type)) {
      listeners.set(type, new Set())
    }
    listeners.get(type).add(handler)
    return () => listeners.get(type)?.delete(handler)
  }

  function emit(type, message) {
    const set = listeners.get(type)
    if (!set) return
    for (const handler of set) {
      try {
        handler(message)
      } catch (err) {
        // 一个订阅者抛错不能影响其它订阅者，更不能把 onmessage 整个打断
        console.error('[useClassSocket] handler error', type, err)
      }
    }
  }

  function buildUrl(id) {
    // 用当前页面的 host 拼绝对地址：
    //  - 开发时走 Vite 的 /ws 代理（vite.config.js 里配了 ws:true）
    //  - 部署后前后端同源时也直接可用
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${protocol}//${window.location.host}/ws/page?sessionId=${encodeURIComponent(id)}`
  }

  function send(payload) {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(payload))
      return true
    }
    return false
  }

  function stopHeartbeat() {
    if (heartbeatTimer) {
      clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function startHeartbeat() {
    stopHeartbeat()
    heartbeatTimer = setInterval(() => send({ type: 'ping' }), HEARTBEAT_MS)
  }

  function scheduleReconnect() {
    if (manuallyClosed) return
    const delay = BACKOFF_STEPS_MS[Math.min(attempt, BACKOFF_STEPS_MS.length - 1)]
    attempt += 1
    reconnectTimer = setTimeout(connect, delay)
  }

  /**
   * 处理一条下行消息。
   *
   * 分工：**内建状态在这里更新**（connected/ended/paused/onlineCount…），
   * 然后**把原始消息原样抛给订阅者**。
   * 这样页面既能直接用现成的 ref，也能订阅到全部细节，
   * 不必为每种事件都加一个 ref。
   */
  function dispatch(msg) {
    switch (msg.type) {
      case 'auth_ok':
        connected.value = true
        lastError.value = ''
        lastErrorCode.value = null
        selfId.value = msg.userId ?? null
        selfRole.value = msg.role ?? null
        onlineCount.value = msg.online ?? 0
        attempt = 0 // 连上了才重置退避，否则网络恢复后会越退越久
        startHeartbeat()
        break

      case 'page':
        // 页码本身由页面自己维护（它还要跟接口兜底值比对），这里只抛出去
        break

      case 'ended':
        ended.value = true
        break

      case 'class.paused':
        paused.value = true
        break

      case 'class.resumed':
        paused.value = false
        break

      case 'presence.join':
        onlineCount.value = msg.online ?? onlineCount.value
        break

      case 'presence.leave':
        onlineCount.value = msg.online ?? onlineCount.value
        break

      case 'error':
        lastError.value = msg.message || '实时连接出错'
        lastErrorCode.value = msg.code ?? null
        break

      default:
        break
    }

    // 无论上面有没有内建处理，都抛给订阅者
    emit(msg.type, msg)
  }

  function connect() {
    if (manuallyClosed) return

    const token = localStorage.getItem('token')
    if (!token) {
      lastError.value = '未登录，无法建立实时连接'
      return
    }

    try {
      socket = new WebSocket(buildUrl(sessionId))
    } catch {
      lastError.value = '无法建立实时连接'
      scheduleReconnect()
      return
    }

    socket.onopen = () => {
      // 第一帧必须是 auth —— 服务端 5 秒内收不到就断开
      socket.send(JSON.stringify({ type: 'auth', token }))
    }

    socket.onmessage = (event) => {
      let msg
      try {
        msg = JSON.parse(event.data)
      } catch {
        return
      }
      dispatch(msg)
    }

    socket.onclose = () => {
      connected.value = false
      stopHeartbeat()
      // onerror 之后一定会触发 onclose，所以重连只在这里调度，避免重复计时器
      scheduleReconnect()
    }
  }

  function close() {
    manuallyClosed = true
    stopHeartbeat()
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (socket) {
      socket.close()
      socket = null
    }
  }

  /** 重连一次（用于「踢人」等需要彻底重建连接的场景）。 */
  function reconnectNow() {
    close()
    manuallyClosed = false
    attempt = 0
    connect()
  }

  // 组件卸载时务必断开，否则每进一次页面就多留一条永不关闭的连接
  onUnmounted(close)

  connect()

  return {
    connected,
    ended,
    paused,
    onlineCount,
    selfId,
    selfRole,
    lastError,
    lastErrorCode,
    on,
    send,
    close,
    reconnectNow,
  }
}
