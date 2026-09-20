// src/composables/useScreenViewer.js
//
// 学生侧：接收老师的屏幕共享画面与麦克风声音（M1）。
//
// 【为什么必须有一个「进入课堂」按钮】
// 带声音的 <video> 自动播放会被浏览器拦截（autoplay policy）。
// 唯一可靠的办法是让**用户点一下**，在那次手势里开始播放。
// 所以这不是可选的体验优化，是硬性前提——没有它，学生看到的是黑屏。
//
// 因此本 composable 用 `entered` 把整个流程挡住：
// 没点之前**连协商都不开始**，点了之后才 prepare() + 告诉老师「我准备好了」。

import { computed, onUnmounted, ref } from 'vue'
import http from '@/api/http'

const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }]

/**
 * @param {object} socket useClassSocket 返回的对象
 * @param {string|number} sessionId
 */
export function useScreenViewer(socket, sessionId) {
  /** 学生点过「进入课堂」没有。它同时是「用户手势已获得」的标志。 */
  const entered = ref(false)
  /** 媒体流。绑到 <video> 的 srcObject 上。 */
  const stream = ref(null)
  /** idle(没在看) / waiting(等老师开) / connecting / live / stopped */
  const state = ref('idle')
  const error = ref('')
  /** play() 被浏览器拦了，需要再点一下才能出声。 */
  const playBlocked = ref(false)
  /** 老师在共享，但学生还没点进来。 */
  const liveButNotEntered = ref(false)

  let pc = null
  let videoEl = null
  /** 老师是谁。回信令必须带上它（课堂里可能有多个老师）。 */
  let teacherId = null
  /** 比 entered 先到的 offer 先存着，等学生点了再处理。 */
  let bufferedOffer = null
  const pendingIce = []

  const hasVideo = computed(() => stream.value !== null)

  function setTeachers(id) {
    if (id) teacherId = id
  }

  function sendIce(candidate) {
    if (!teacherId) return
    socket.send({ type: 'webrtc.ice', toUserId: teacherId, candidate })
  }

  /** 建立连接并告诉老师「可以发流了」。重复调用是安全的。 */
  function prepare() {
    if (pc) {
      // 已经建过：再喊一次 ready，覆盖「老师在我们建连之后才开始共享」的情况
      socket.send({ type: 'webrtc.ready' })
      return pc
    }
    if (!entered.value) return null

    pc = new RTCPeerConnection({ iceServers: ICE_SERVERS })

    pc.ontrack = (event) => {
      stream.value = event.streams[0] || new MediaStream([event.track])
      state.value = 'live'
      if (videoEl) {
        videoEl.srcObject = stream.value
        videoEl.play().catch(() => {
          // 自动播放被拦（多见于 Safari）。交给页面显示一个「点我播放」的兜底按钮。
          playBlocked.value = true
        })
      }
    }

    pc.onicecandidate = (event) => {
      if (event.candidate) sendIce(event.candidate)
    }

    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'connected') {
        state.value = 'live'
      } else if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        // 不在这里自动重连：老师重建连接时会重新发 offer。
        // 学生侧硬要重连反而容易和老师的重建打架。
        state.value = 'connecting'
      }
    }

    // 主动告诉老师可以发流了
    socket.send({ type: 'webrtc.ready' })
    state.value = 'connecting'
    return pc
  }

  /** 学生点「进入课堂」。这是唯一能解锁音视频播放的用户手势。 */
  async function enter() {
    entered.value = true
    error.value = ''
    await refresh()
    if (state.value === 'idle') {
      state.value = 'waiting'
    }
    // 有比手势先到的 offer，现在处理
    if (bufferedOffer) {
      const offer = bufferedOffer
      bufferedOffer = null
      await handleOffer(offer)
    } else if (liveButNotEntered.value) {
      prepare()
    }
  }

  /** 查一下老师现在在不在共享。学生一进课堂就该知道。 */
  async function refresh() {
    try {
      const data = await http.get(`/session/${sessionId}/stream`)
      if (data.live) {
        setTeachers(data.teacherId)
        liveButNotEntered.value = true
        if (entered.value) prepare()
      } else {
        liveButNotEntered.value = false
        if (state.value !== 'live') state.value = 'waiting'
      }
    } catch {
      // 查不到不影响后续：老师开共享时会广播 stream.started
      if (entered.value) state.value = 'waiting'
    }
  }

  async function handleOffer(msg) {
    if (!entered.value) {
      // 学生还没点「进入课堂」。先存着——协议协商可以先跑，
      // 但没有用户手势，带声音的播放一定被拦，不如等他点。
      bufferedOffer = msg
      liveButNotEntered.value = true
      state.value = 'waiting'
      return
    }

    setTeachers(msg.fromUserId)
    const conn = prepare()
    if (!conn) return

    try {
      await conn.setRemoteDescription(msg.sdp)
      // 补上比 offer 先到的候选
      for (const candidate of pendingIce.splice(0)) {
        try {
          await conn.addIceCandidate(candidate)
        } catch {
          /* 忽略单条失败 */
        }
      }
      const answer = await conn.createAnswer()
      await conn.setLocalDescription(answer)
      socket.send({ type: 'webrtc.answer', toUserId: teacherId, sdp: conn.localDescription })
    } catch (e) {
      error.value = '与老师的连接建立失败'
      state.value = 'waiting'
    }
  }

  function teardown() {
    if (pc) {
      try {
        pc.close()
      } catch {
        /* 忽略 */
      }
      pc = null
    }
    stream.value = null
    if (videoEl) videoEl.srcObject = null
    pendingIce.length = 0
    bufferedOffer = null
    playBlocked.value = false
  }

  /** 把 <video> 元素交给 composable 管理（绑定流、调 play）。 */
  function attach(element) {
    videoEl = element
    if (videoEl && stream.value) {
      videoEl.srcObject = stream.value
      videoEl.play().catch(() => {
        playBlocked.value = true
      })
    }
  }

  /** 用户在「点我播放」按钮里手动触发播放。 */
  async function playNow() {
    if (!videoEl) return
    try {
      await videoEl.play()
      playBlocked.value = false
    } catch {
      playBlocked.value = true
    }
  }

  // ── 订阅服务端事件 ─────────────────────────────────────

  const offs = [
    socket.on('stream.started', (msg) => {
      setTeachers(msg.teacherId)
      liveButNotEntered.value = true
      if (entered.value) prepare()
    }),

    socket.on('stream.stopped', () => {
      // 老师停了共享。**清干净并明说**，不要留个黑屏让学生以为是自己卡了。
      teardown()
      liveButNotEntered.value = false
      state.value = 'stopped'
    }),

    socket.on('webrtc.offer', (msg) => handleOffer(msg)),

    socket.on('webrtc.ice', async (msg) => {
      if (!pc || !pc.remoteDescription) {
        // 比 offer 先到的候选先存着
        pendingIce.push(msg.candidate)
        return
      }
      try {
        await pc.addIceCandidate(msg.candidate)
      } catch {
        /* 忽略单条失败 */
      }
    }),
  ]

  onUnmounted(() => {
    offs.forEach((off) => off())
    teardown()
  })

  return {
    entered,
    stream,
    state,
    error,
    playBlocked,
    liveButNotEntered,
    hasVideo,
    enter,
    refresh,
    attach,
    playNow,
    teardown,
  }
}
