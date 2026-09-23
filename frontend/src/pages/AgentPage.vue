<script setup>
// AI 智能体控制台。
//
// 与 /ai（纯聊天）是**两个独立页面**：那边的助手只能一问一答，
// 这边能让模型真的去查数据、并往服务器上写文件。
// 所以这里的入口限教师与管理员 —— 而且真正的墙在后端
// （SecurityConfig 里 /api/agent/** → hasAnyRole('TEACHER','ADMIN')），
// 这个页面的路由守卫只是体验层。
//
// 交互上的三个约定（Friday 拍板）：
//   1. 左栏放「上下文 + 快捷任务」，**执行按钮在右侧**；
//   2. 快捷任务与自由输入是**同一条路** —— 快捷任务只是把预置指令填进输入框，
//      用户可以再改，然后统一走「执行」。不搞两套逻辑。
//   3. 任务要跑十几秒，必须让老师看到「走到第几步了」。
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { showToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import FcButton from '@/components/base/FcButton.vue'
import FcCard from '@/components/base/FcCard.vue'
import FcEmptyState from '@/components/base/FcEmptyState.vue'
import FcTag from '@/components/base/FcTag.vue'
import {
  createFolder,
  downloadFile,
  fetchTask,
  listCoursewares,
  listFiles,
  listFolders,
  listFormats,
  listTaughtSessions,
  startTask,
} from '@/api/agent'

/** 三个快捷任务。点一下只是把 instruction 填进输入框，可再改。 */
const QUICK_TASKS = [
  {
    key: 'review',
    label: '整理复习资料',
    instruction: '帮我整理一份这节课的复习资料，按知识点组织，最后附上学生提问的要点。',
  },
  {
    key: 'quiz',
    label: '出一套练习题',
    instruction: '根据这节课的内容出一套练习题，选择题与简答题各若干，并附参考答案。',
  },
  {
    key: 'questions',
    label: '汇总学生提问',
    instruction: '汇总本节课学生的提问，按主题归类，并指出哪些是普遍性的困惑。',
  },
]

/** 轮询间隔。与课件解析那套一致，1.5 秒对「十几秒的任务」够用又不吵。 */
const POLL_MS = 1500

const auth = useAuthStore()
const coursewares = ref([])
const sessions = ref([])
const folders = ref([])
const formats = ref([])
const files = ref([])

const form = ref({ coursewareId: '', sessionId: '', folder: '', format: 'docx' })
const instruction = ref('')
const activeQuick = ref('')
const newFolder = ref('')

const task = ref(null)
const messages = ref([])
const conversation = ref(null)

/**
 * 轮询状态。
 *
 * ⚠ 这里刻意用 {@code setTimeout} **串行链** 而不是 {@code setInterval}。
 * setInterval 不会等上一次请求回来，1.5 秒一个、而单次请求可能超过 1.5 秒，
 * 于是多个请求同时在飞、响应按到达顺序覆盖 —— 一个慢的旧响应会把
 * 已经到达的「SUCCESS」重新盖回「RUNNING」，而那时定时器已经被停掉了，
 * 界面就永久卡在「正在处理…」、执行按钮永久禁用，只能刷新页面。
 * 串行链天然不可能重叠，从根上消掉这一类。
 */
let pollTimer = null
/** 组件已卸载。正在飞的 POST 回来时会看到它，从而不再启动轮询。 */
let disposed = false
/** 连读多少次轮询失败才放弃。单次网络抖动不该把还在跑的任务抛掉。 */
let consecutiveErrors = 0
let messageSeq = 0

/** 单次任务最多轮询多少次（200 × 1.5 秒 = 5 分钟），防止后端卡住时无限打接口。 */
const MAX_POLLS = 200
/** 连续轮询失败达到这个次数就停：再打也只是刷屏。 */
const MAX_POLL_ERRORS = 3

/** 发起请求到拿到 taskId 之间的窗口，用来挡住连点两次执行。 */
const starting = ref(false)

const running = computed(() => task.value?.status === 'RUNNING')
// 必须同时要求「选了文件夹」：后端 AgentTaskRequest 上 folder 是 @NotBlank，
// 不选的话点下去只会拿到一句后端报错。前端能提前拦的就别丢给后端。
const canRun = computed(
  () => !running.value && !starting.value && !!form.value.folder && instruction.value.trim().length > 0,
)

const FORMAT_LABELS = { md: 'Markdown', txt: '文本', docx: 'Word', xlsx: 'Excel' }

onMounted(async () => {
  const jobs = [loadCoursewares(), loadFolders(), loadFormats()]
  // ⚠ 课堂下拉的接口是 GET /api/session/taught，它带着
  //   @PreAuthorize("hasRole('TEACHER')") —— **管理员调用会 403**。
  //   这不是能绕过的：那个接口的语义就是「我教过的课」，管理员没有这个集合。
  //   所以只对教师拉取；管理员那边下拉直接禁用并说明原因，
  //   而不是打一个注定 403 的请求、再弹一个看不懂的红字。
  if (auth.isTeacher) {
    jobs.push(loadSessions())
  }
  await Promise.all(jobs)
  await loadFiles()
})

// 离开页面必须停掉轮询：否则用户切走后定时器还在打接口，
// 而且回来时会看到一堆过期响应把界面刷回去。
//
// ⚠ disposed 必须先置位再停表：如果用户是在「POST 还在飞」的时候切走的，
//   那一刻 pollTimer 还是 null、停表是个空操作，而 POST 回来之后
//   照样会装上轮询 —— 那个定时器再也没人停得掉，会一直陪着标签页。
onBeforeUnmount(() => {
  disposed = true
  stopPolling()
})

async function loadCoursewares() {
  try {
    const res = await listCoursewares()
    coursewares.value = res?.list || []
  } catch (err) {
    showToast(err.message, 'error')
  }
}

async function loadSessions() {
  try {
    const res = await listTaughtSessions()
    sessions.value = res?.list || []
  } catch (err) {
    showToast(err.message, 'error')
  }
}

async function loadFolders() {
  try {
    const res = await listFolders()
    folders.value = res?.list || []
    if (!form.value.folder && folders.value.length) {
      form.value.folder = folders.value[0]
    }
  } catch (err) {
    showToast(err.message, 'error')
  }
}

async function loadFormats() {
  try {
    const res = await listFormats()
    formats.value = res || []
  } catch (err) {
    showToast(err.message, 'error')
  }
}

async function loadFiles() {
  try {
    const res = await listFiles()
    files.value = res?.list || []
  } catch (err) {
    showToast(err.message, 'error')
  }
}

async function onCreateFolder() {
  const name = newFolder.value.trim()
  if (!name) {
    showToast('请先填写文件夹名', 'warning')
    return
  }
  try {
    await createFolder(name)
    newFolder.value = ''
    await loadFolders()
    form.value.folder = name
    showToast('文件夹已创建', 'success')
  } catch (err) {
    // 沙箱的拒绝信息（如「文件夹名不能包含路径分隔符」）会原样带到这里
    showToast(err.message, 'error')
  }
}

function pickQuick(item) {
  activeQuick.value = item.key
  instruction.value = item.instruction
}

function pushMessage(role, text, kind = 'normal') {
  messageSeq += 1
  messages.value.push({ id: messageSeq, role, text, kind })
  scrollToBottom()
}

function scrollToBottom() {
  nextTick(() => {
    const box = conversation.value
    if (box) box.scrollTop = box.scrollHeight
  })
}

async function onRun() {
  if (!canRun.value) return

  const text = instruction.value.trim()
  pushMessage('user', text)
  instruction.value = ''
  activeQuick.value = ''
  starting.value = true

  try {
    const started = await startTask({
      instruction: text,
      coursewareId: form.value.coursewareId || null,
      sessionId: form.value.sessionId || null,
      folder: form.value.folder,
      format: form.value.format,
    })
    if (disposed) return
    task.value = started
    startPolling(started.taskId)
  } catch (err) {
    // 400/403 这类在**发起时**就能发现的问题（越权选了别人的课件、
    // 文件夹名不合法）会走到这里，不必等模型跑完才发现。
    // 把原话还给用户 —— 否则他得重新打一遍（多打一句就要重打一千字）。
    instruction.value = text
    pushMessage('agent', err.message, 'error')
    showToast(err.message, 'error')
  } finally {
    starting.value = false
  }
}

function startPolling(taskId) {
  stopPolling()
  consecutiveErrors = 0
  scheduleNextPoll(taskId, 1)
}

function stopPolling() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

/** 串行链：只有上一次处理完（无论成败）才排下一次。 */
function scheduleNextPoll(taskId, attempt) {
  pollTimer = setTimeout(() => pollOnce(taskId, attempt), POLL_MS)
}

async function pollOnce(taskId, attempt) {
  if (disposed) return

  let data
  try {
    data = await fetchTask(taskId)
  } catch (err) {
    if (disposed) return
    consecutiveErrors += 1
    if (consecutiveErrors < MAX_POLL_ERRORS) {
      // 单次网络抖动不该把还在跑的任务抛掉——那会让按钮看起来又能点了，
      // 老师再点一次就是**第二个付费任务**，而第一个的结果再也没人看。
      scheduleNextPoll(taskId, attempt)
      return
    }
    stopPolling()
    task.value = null
    pushMessage('agent',
      `连续 ${MAX_POLL_ERRORS} 次查询任务状态失败（${err.message}）。`
      + '任务可能仍在后台运行，稍后刷新页面看「生成的文件」即可。', 'error')
    return
  }

  if (disposed) return
  consecutiveErrors = 0
  task.value = data

  if (data.status !== 'RUNNING') {
    stopPolling()
    await finishTask(data)
    return
  }

  if (attempt >= MAX_POLLS) {
    // 后端卡住（比如工人线程没了）时不能无限打接口，也不能让界面永远转圈
    stopPolling()
    task.value = null
    pushMessage('agent',
      '任务长时间没有结束，已经停止查询。请刷新页面后在「生成的文件」里确认结果。', 'warning')
    return
  }

  scheduleNextPoll(taskId, attempt + 1)
}

async function finishTask(data) {
  if (data.status === 'SUCCESS') {
    pushMessage('agent', data.result || '任务已完成。')
  } else if (data.status === 'LIMIT') {
    // 步数用尽**不是失败**，但必须让老师知道它没干完 —— 不做静默截断。
    // 兜底文案不能省：LIMIT 的 result 理论上一定有值（后端组装过），
    // 但真为空时会渲染出一个带警告底色、里面什么都没有的气泡 ——
    // 那比不提示更让人迷惑。
    pushMessage('agent',
      data.result || '这个任务超过了步数上限，已经停下，还没有全部完成。建议把要求拆小一点再试一次。',
      'warning')
  } else {
    pushMessage('agent', data.error || '任务执行失败。', 'error')
  }
  task.value = null
  await loadFiles()
}

async function onDownload(file) {
  try {
    await downloadFile(file)
  } catch (err) {
    showToast(err.message, 'error')
  }
}

function humanSize(bytes) {
  // 判的是 null / undefined 而不是 falsy：一个 0 字节的文件是真的存在，
  // 显示成「—」（未知大小）会让老师以为记录坏了。
  if (bytes === null || bytes === undefined) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div class="agent">
    <header class="agent__head">
      <h1 class="agent__title">AI 助手</h1>
      <p class="agent__sub">
        选好上下文，用一句话让它去查资料、产出一份能下载的文件。
        它只能看到你选定的课件与课堂，读不到别人的。
      </p>
    </header>

    <div class="agent__body">
      <!-- ── 左栏：上下文 + 快捷任务 ───────────────────────── -->
      <aside class="side">
        <FcCard title="上下文" subtitle="决定它能查到什么、写到哪">
          <div class="field">
            <label class="field__label" for="ag-courseware">课件</label>
            <select id="ag-courseware" v-model="form.coursewareId" class="field__select">
              <option value="">不选（读不到课件内容）</option>
              <option v-for="c in coursewares" :key="c.id" :value="c.id">
                {{ c.name }}
              </option>
            </select>
          </div>

          <div class="field">
            <label class="field__label" for="ag-session">课堂</label>
            <select
              id="ag-session"
              v-model="form.sessionId"
              class="field__select"
              :disabled="!auth.isTeacher"
            >
              <option value="">
                {{ auth.isTeacher ? '不选（读不到课堂记录）' : '管理员账号没有自己的课堂' }}
              </option>
              <option v-for="s in sessions" :key="s.id" :value="s.id">
                {{ s.title || '未命名课堂' }}
              </option>
            </select>
          </div>

          <div class="field">
            <label class="field__label" for="ag-folder">文件夹</label>
            <select id="ag-folder" v-model="form.folder" class="field__select">
              <option v-for="f in folders" :key="f" :value="f">{{ f }}</option>
            </select>
          </div>

          <div class="field field--inline">
            <input
              v-model="newFolder"
              class="field__input"
              type="text"
              placeholder="新建文件夹…"
              aria-label="新建文件夹名称"
              @keyup.enter="onCreateFolder"
            />
            <FcButton variant="secondary" size="sm" @click="onCreateFolder">新建</FcButton>
          </div>

          <div class="field">
            <label class="field__label" for="ag-format">输出格式</label>
            <select id="ag-format" v-model="form.format" class="field__select">
              <option v-for="f in formats" :key="f" :value="f">
                {{ FORMAT_LABELS[f] || f }}
              </option>
            </select>
          </div>
        </FcCard>

        <FcCard title="快捷任务" subtitle="点一下填进右边输入框，可以再改">
          <button
            v-for="item in QUICK_TASKS"
            :key="item.key"
            class="quick"
            :class="{ 'quick--on': activeQuick === item.key }"
            type="button"
            @click="pickQuick(item)"
          >
            {{ item.label }}
          </button>
        </FcCard>
      </aside>

      <!-- ── 右栏：执行 + 对话 ─────────────────────────────── -->
      <section class="main">
        <div class="compose">
          <div class="compose__top">
            <!--
              role="status" 让读屏软件能听到进度。
              任务要跑十几秒到一分钟，而对话区的 aria-live 只在**结束时**
              才推消息 —— 不标在这里的话，中途对读屏用户是完全静默的。
            -->
            <span class="compose__status" role="status" aria-live="polite">
              <!--
                只显示后端给的 stepText，**不要**再拼一次步数。
                后端的文案里已经带了（「正在继续处理（第 3 步）…」），
                而 task.steps 要到任务结束才落库，运行中恒为 0 ——
                拼上去会变成「（第 3 步）…（第 0 步）」这种自相矛盾的提示。
              -->
              <template v-if="running">
                <span class="dot" aria-hidden="true" />
                {{ task.stepText || '正在处理…' }}
              </template>
              <template v-else>准备就绪</template>
            </span>
            <FcButton :disabled="!canRun" :loading="running" @click="onRun">执 行</FcButton>
          </div>
          <textarea
            v-model="instruction"
            class="compose__input"
            rows="3"
            maxlength="1000"
            placeholder="让它做什么…（Ctrl + Enter 直接执行）"
            aria-label="任务要求"
            @input="activeQuick = ''"
            @keydown.ctrl.enter="onRun"
            @keydown.meta.enter="onRun"
          />
        </div>

        <div ref="conversation" class="chat" aria-live="polite">
          <FcEmptyState
            v-if="!messages.length"
            title="还没有任务"
            description="先选好左边的上下文，再点一个快捷任务，或者直接写下你的要求。"
          />
          <div
            v-for="m in messages"
            :key="m.id"
            class="msg"
            :class="[`msg--${m.role}`, { 'msg--error': m.kind === 'error', 'msg--warning': m.kind === 'warning' }]"
          >
            <div class="msg__who">{{ m.role === 'user' ? '我' : 'AI 助手' }}</div>
            <div class="msg__text">{{ m.text }}</div>
          </div>
        </div>

        <FcCard title="生成的文件" subtitle="只有你自己能看到、能下载" padding="none">
          <FcEmptyState
            v-if="!files.length"
            size="sm"
            title="还没有生成过文件"
            description="任务完成后，产出的文件会出现在这里。"
          />
          <ul v-else class="files">
            <li v-for="f in files" :key="f.id" class="files__row">
              <span class="files__name">{{ f.filename }}</span>
              <FcTag size="sm">{{ FORMAT_LABELS[f.format?.toLowerCase()] || f.format }}</FcTag>
              <span class="files__meta">{{ f.folder }} · {{ humanSize(f.sizeBytes) }}</span>
              <!-- 每行都是「下载」，读屏用户听到的是 N 个一模一样的按钮；
                   把文件名带上才有区分度。 -->
              <FcButton
                variant="ghost"
                size="sm"
                :aria-label="`下载 ${f.filename}`"
                @click="onDownload(f)"
              >
                下载
              </FcButton>
            </li>
          </ul>
        </FcCard>
      </section>
    </div>
  </div>
</template>

<style scoped>
.agent {
  max-width: 1120px;
  margin: 0 auto;
}

.agent__head {
  margin-bottom: 18px;
}

.agent__title {
  font-size: 22px;
  color: #222;
}

.agent__sub {
  margin-top: 6px;
  font-size: 13px;
  color: #888;
}

.agent__body {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 18px;
  align-items: start;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field {
  margin-bottom: 12px;
}

.field--inline {
  display: flex;
  gap: 8px;
  align-items: center;
}

.field__label {
  display: block;
  margin-bottom: 5px;
  font-size: 12px;
  color: #666;
}

.field__select,
.field__input {
  width: 100%;
  padding: 8px 10px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 13px;
  color: #333;
  background: #fff;
}

.field__select:focus,
.field__input:focus {
  outline: none;
  border-color: #d97757;
}

.quick {
  display: block;
  width: 100%;
  margin-bottom: 8px;
  padding: 10px 12px;
  border: 1px solid #e6e6e6;
  border-radius: 8px;
  background: #fff;
  font-size: 13px;
  color: #444;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
}

.quick:hover {
  border-color: #d97757;
  color: #d97757;
}

.quick--on {
  border-color: #d97757;
  background: #fdf6f2;
  color: #d97757;
  font-weight: 600;
}

.main {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.compose {
  background: #fff;
  border: 1px solid #eee;
  border-radius: 12px;
  padding: 12px 14px;
}

.compose__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.compose__status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #888;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #d97757;
  animation: pulse 1.1s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.25; }
}

.compose__input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 13px;
  font-family: inherit;
  line-height: 1.6;
  resize: vertical;
}

.compose__input:focus {
  outline: none;
  border-color: #d97757;
}

.chat {
  min-height: 260px;
  max-height: 460px;
  overflow-y: auto;
  padding: 14px;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 12px;
}

.msg {
  margin-bottom: 14px;
}

.msg__who {
  font-size: 12px;
  color: #999;
  margin-bottom: 4px;
}

.msg__text {
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f7f7f8;
}

.msg--user .msg__text {
  background: #fdf6f2;
}

.msg--error .msg__text {
  background: #fdeaea;
  color: #b93a2b;
}

.msg--warning .msg__text {
  background: #fff7e6;
  color: #8a5a00;
}

.files {
  list-style: none;
}

.files__row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid #f4f4f4;
  font-size: 13px;
}

.files__row:last-child {
  border-bottom: none;
}

.files__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #333;
}

.files__meta {
  font-size: 12px;
  color: #999;
}

@media (max-width: 900px) {
  .agent__body {
    grid-template-columns: 1fr;
  }
}
</style>
