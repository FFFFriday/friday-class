// src/composables/useScreenShare.js
//
// 老师侧：采集屏幕 + 麦克风，为每个学生建立一条点对点连接（M1）。
//
// 【为什么是点对点而不是媒体服务器】
// 本地演示 + 2 个学生，直接连就够了，延迟甚至比走服务器中转还低。
// 腾讯会议要在中间架 SFU，是因为它要解决公网、几十人、弱网——
// 那些问题本项目没有。代价是：老师要为**每个学生**独立编码一份，
// 所以学生数不宜多（演示场景控制在 2 人）。
//
// 【信令】走已有的 WebSocket，服务端只按 toUserId 转发，不解析 SDP。

import { computed, onUnmounted, ref } from 'vue'
import http from '@/api/http'

/**
 * localhost 直连用不到 STUN（双方都在本机），但留着无妨：
 * 万一将来换成局域网演示，没有它就连不上。
 */
const ICE_SERVERS = [{ urls: 'stun:stun.l.google.com:19302' }]

/** 屏幕内容是文字，优先保清晰度。2Mbps 对 1080p 文字画面够用且不浪费。 */
const MAX_VIDEO_BITRATE = 2_000_000

export function useScreenShare(socket, sessionId) {
  const sharing = ref(false)
  const starting = ref(false)
  const stopping = ref(false)
  const error = ref('')
  /** 麦克风没拿到时的提示。**不阻断共享**——画面才是主体。 */
  const micWarning = ref('')

  /** 课堂里现在有谁（含老师自己）。 */
  const participants = ref([])
  /** userId → 连接状态（new/connecting/connected/failed/closed）。 */
  const peerStates = ref({})

  const peers = new Map()
  /** 先到一步的 ICE 候选，等 remoteDescription 设好再补进去。 */
  const pendingIce = new Map()
  let localStream = null

  const students = computed(() => participants.value.filter((p) => p.role === 'STUDENT'))

  const connectedCount = computed(
    () => Object.values(peerStates.value).filter((s) => s === 'connected').length,
  )

  function setPeerState(userId, state) {
    peerStates.value = { ...peerStates.value, [userId]: state }
  }

  function upsertParticipant(msg) {
    const exists = participants.value.some((p) => String(p.userId) === String(msg.userId))
    if (exists) return
    participants.value = [
      ...participants.value,
      { userId: msg.userId, nickname: msg.nickname, role: msg.role },
    ]
  }

  function removeParticipant(userId) {
    participants.value = participants.value.filter((p) => String(p.userId) !== String(userId))
  }

  /** 拉一次完整在线名单。只靠事件累积的话，老师一刷新名单就空了。 */
  async function loadOnline() {
    try {
      const data = await http.get(`/session/${sessionId}/online`)
      participants.value = data.list || []
    } catch {
      // 名单拉不到不影响共享本身，静默即可
    }
  }

  async function flushIce(userId, pc) {
    const queued = pendingIce.get(userId)
    if (!queued?.length) return
    pendingIce.set(userId, [])
    for (const candidate of queued) {
      try {
        await pc.addIceCandidate(candidate)
      } catch {
        // 单条候选失败不影响其它候选
      }
    }
  }

  /** 为某个学生建一条连接并主动发 offer。重复调用是安全的（幂等）。 */
  function createPeer(userId) {
    if (!localStream) return null
    if (peers.has(userId)) return peers.get(userId)

    const pc = new RTCPeerConnection({ iceServers: ICE_SERVERS })
    peers.set(userId, pc)
    pendingIce.set(userId, [])
    setPeerState(userId, 'connecting')

    for (const track of localStream.getTracks()) {
      pc.addTrack(track, localStream)
    }

    // 限制码率。某些浏览器在 addTrack 后立刻读 encodings 会拿到空数组，
    // 所以补齐一个再设，且失败就忽略——设置失败只是码率不受控，不影响连通。
    const videoSender = pc.getSenders().find((s) => s.track?.kind === 'video')
    if (videoSender) {
      try {
        const params = videoSender.getParameters()
        if (!params.encodings || !params.encodings.length) {
          params.encodings = [{}]
        }
        params.encodings[0].maxBitrate = MAX_VIDEO_BITRATE
        videoSender.setParameters(params)
      } catch {
        /* 忽略 */
      }
    }

    pc.onicecandidate = (event) => {
      if (event.candidate) {
        socket.send({ type: 'webrtc.ice', toUserId: userId, candidate: event.candidate })
      }
    }

    pc.onconnectionstatechange = () => setPeerState(userId, pc.connectionState)

    // 学生掉线重进会走 presence.join → 这里重建；旧的先关掉
    pc.createOffer()
      .then((offer) => pc.setLocalDescription(offer))
      .then(() => {
        socket.send({ type: 'webrtc.offer', toUserId: userId, sdp: pc.localDescription })
      })
      .catch(() => setPeerState(userId, 'failed'))

    return pc
  }

  function closePeer(userId) {
    const pc = peers.get(userId)
    if (pc) {
      try {
        pc.close()
      } catch {
        /* 忽略 */
      }
      peers.delete(userId)
    }
    pendingIce.delete(userId)
    const next = { ...peerStates.value }
    delete next[userId]
    peerStates.value = next
  }

  /** 停掉本地所有轨道、关掉所有连接。不碰服务端登记，由调用方决定。 */
  function cleanupLocal() {
    for (const userId of [...peers.keys()]) {
      closePeer(userId)
    }
    if (localStream) {
      localStream.getTracks().forEach((t) => {
        try {
          t.stop()
        } catch {
          /* 忽略 */
        }
      })
      localStream = null
    }
  }

  async function start() {
    if (sharing.value || starting.value) return
    error.value = ''
    micWarning.value = ''
    starting.value = true

    try {
      if (!navigator.mediaDevices?.getDisplayMedia) {
        throw new Error(
          '当前浏览器不支持屏幕共享，或页面不在安全上下文（必须是 https 或 localhost）',
        )
      }

      // 1) 屏幕画面。**不要 PPT 里的声音**（需求明确），音频只走麦克风。
      const display = await navigator.mediaDevices.getDisplayMedia({
        video: { frameRate: 20, width: { max: 1920 }, height: { max: 1080 } },
        audio: false,
      })

      // 2) 麦克风。失败不阻断共享：学生看得到画面也远好过什么都收不到。
      let mic = null
      try {
        mic = await navigator.mediaDevices.getUserMedia({ audio: true })
      } catch {
        micWarning.value = '麦克风未授权，学生只能看到画面、听不到声音'
      }

      const tracks = [...display.getVideoTracks(), ...(mic ? mic.getAudioTracks() : [])]
      localStream = new MediaStream(tracks)

      // contentHint = 'text'：告诉编码器这是文字/图表画面，
      // 优先保清晰度而不是运动流畅度。PPT 场景收益明显。
      for (const track of display.getVideoTracks()) {
        try {
          track.contentHint = 'text'
        } catch {
          /* 部分浏览器不支持，忽略 */
        }
      }

      // 老师在浏览器自带的共享条上点「停止共享」时，这里也要跟着收尾，
      // 否则服务端状态还停在「正在共享」，学生会一直等一个不存在的画面。
      for (const track of display.getVideoTracks()) {
        track.addEventListener('ended', () => {
          if (sharing.value) stop()
        })
      }

      await http.post(`/session/${sessionId}/stream`, { live: true })
      sharing.value = true

      await loadOnline()
      for (const student of students.value) {
        createPeer(student.userId)
      }
    } catch (e) {
      cleanupLocal()
      error.value =
        e?.name === 'NotAllowedError'
          ? '已取消屏幕共享授权'
          : e?.message || '开启共享失败'
    } finally {
      starting.value = false
    }
  }

  async function stop() {
    if (!sharing.value || stopping.value) return
    stopping.value = true
    // 先置位再清理：清理会触发 video track 的 ended，那个回调又调 stop()，
    // 不先置位就会递归进来
    sharing.value = false
    try {
      cleanupLocal()
      await http.post(`/session/${sessionId}/stream`, { live: false })
    } catch (e) {
      error.value = e.message || '停止共享时出错'
    } finally {
      stopping.value = false
    }
  }

  // ── 订阅信令与在线事件 ─────────────────────────────────

  const offs = [
    socket.on('webrtc.answer', async (msg) => {
      const pc = peers.get(msg.fromUserId)
      if (!pc) return
      try {
        await pc.setRemoteDescription(msg.sdp)
        await flushIce(msg.fromUserId, pc)
      } catch {
        setPeerState(msg.fromUserId, 'failed')
      }
    }),

    socket.on('webrtc.ice', async (msg) => {
      const pc = peers.get(msg.fromUserId)
      if (!pc) return
      // 候选可能比 answer 先到：remoteDescription 还没设时先存起来
      if (!pc.remoteDescription) {
        const queued = pendingIce.get(msg.fromUserId) || []
        queued.push(msg.candidate)
        pendingIce.set(msg.fromUserId, queued)
        return
      }
      try {
        await pc.addIceCandidate(msg.candidate)
      } catch {
        /* 单条失败可忽略 */
      }
    }),

    socket.on('webrtc.ready', (msg) => {
      // 学生说「我准备好了」。正在共享就立刻建连；
      // 没在共享则什么都不做——等他点「开始共享」时再统一建。
      if (sharing.value) createPeer(msg.fromUserId)
    }),

    socket.on('presence.join', (msg) => {
      if (msg.role !== 'STUDENT') return
      upsertParticipant(msg)
      if (sharing.value) createPeer(msg.userId)
    }),

    socket.on('presence.leave', (msg) => {
      removeParticipant(msg.userId)
      closePeer(msg.userId)
    }),
  ]

  onUnmounted(() => {
    offs.forEach((off) => off())
    cleanupLocal()
  })

  return {
    sharing,
    starting,
    stopping,
    error,
    micWarning,
    participants,
    students,
    peerStates,
    connectedCount,
    loadOnline,
    start,
    stop,
  }
}
