// src/composables/usePageSync.js
//
// 跟课堂的 WebSocket 保持连接，把「老师翻到第几页」变成一个响应式的 ref。
//
// 为什么需要它：HTTP 是「客户端问、服务端答」，服务端没有嘴，没法主动告诉学生
// 「翻页了」。要么每 2 秒轮询一次（40 个学生 × 每 2 秒 = 每秒 20 个空请求），
// 要么保持一条长连接、服务端有变化就推过来。这里是后者。
//
// ⚠️ 鉴权注意：浏览器原生的 `new WebSocket(url)` **不能设置请求头**，
// 所以 REST 那套 `Authorization: Bearer` 在这里用不了。改成「连上后第一帧发 token」，
// 服务端 5 秒内收不到就断开。

import { ref, onUnmounted } from 'vue'

/** 重连退避：1s → 2s → 4s → 8s … 上限 30s，避免服务端一挂就被全员同时重连打爆。 */
const BACKOFF_STEPS_MS = [1000, 2000, 4000, 8000, 16000, 30000]

/** 心跳间隔。太短浪费，太长会被中间的代理/网关当成空闲连接掐掉。 */
const HEARTBEAT_MS = 25000

/**
 * @param {string|number} sessionId 课堂 ID
 * @param {(pageNo: number) => void} [onPageChange] 每次收到翻页广播时回调
 * @param {() => void} [onEnded] 收到「下课」广播时回调
 */
export function usePageSync(sessionId, onPageChange, onEnded) {
  const currentPage = ref(null)
  const connected = ref(false)
  const lastError = ref('')
  /** 服务端广播了下课。一旦为 true 就不再回退——课结束了不会「重新开始」。 */
  const ended = ref(false)

  let socket = null
  let heartbeatTimer = null
  let reconnectTimer = null
  let attempt = 0
  let manuallyClosed = false

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
    }
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

      if (msg.type === 'auth_ok') {
        connected.value = true
        lastError.value = ''
        attempt = 0 // 连上了才重置退避，否则网络恢复后会越退越久
        startHeartbeat()
        return
      }

      if (msg.type === 'page') {
        currentPage.value = msg.pageNo
        if (onPageChange) onPageChange(msg.pageNo)
        return
      }

      if (msg.type === 'ended') {
        // 只置位、不关闭连接：老师可能只是误点后重新开课，
        // 断开反而会让两端各自重连、状态更难对齐。
        ended.value = true
        if (onEnded) onEnded()
        return
      }

      if (msg.type === 'error') {
        lastError.value = msg.message || '实时连接出错'
      }
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

  // 组件卸载时务必断开，否则每进一次页面就多留一条永不关闭的连接
  onUnmounted(close)

  connect()

  return { currentPage, connected, lastError, ended, close }
}
