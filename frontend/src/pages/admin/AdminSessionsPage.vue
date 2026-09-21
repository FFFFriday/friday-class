<script setup>
// 管理端：课堂管理。含「暂停所有课堂」与踢人。
import { onMounted, reactive, ref } from 'vue'
import http from '@/api/http'
import { useToast } from '@/composables/useToast'
import { confirm, prompt } from '@/composables/useConfirm'
import { FcButton, FcCard, FcLoading, FcModal, FcTable, FcTag } from '@/components/base'

const toast = useToast()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const error = ref('')
const busy = ref(false)

const filters = reactive({ status: '', keyword: '' })

const columns = [
  { key: 'title', title: '课堂' },
  { key: 'coursewareName', title: '课件' },
  { key: 'teacherName', title: '教师', width: '120px' },
  { key: 'status', title: '状态', width: '90px' },
  { key: 'currentPage', title: '当前页', width: '80px' },
  { key: 'ops', title: '操作', width: '300px' },
]

const STATUS_TEXT = { NOT_STARTED: '未开始', LIVE: '直播中', PAUSED: '已暂停', ENDED: '已结束' }
const STATUS_TAG = { NOT_STARTED: 'default', LIVE: 'danger', PAUSED: 'warning', ENDED: 'default' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = { page: page.value, size }
    if (filters.status) params.status = filters.status
    if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
    const data = await http.get('/admin/sessions', { params })
    rows.value = data.list || []
    total.value = data.total || 0
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function goPage(delta) {
  const next = page.value + delta
  if (next < 1 || next > Math.ceil(total.value / size)) return
  page.value = next
  load()
}

async function rowOp(row, action, label, confirmText) {
  if (confirmText) {
    const ok = await confirm({ title: label, message: confirmText, confirmText: '确定', danger: true })
    if (!ok) return
  }
  busy.value = true
  try {
    await http.post(`/admin/sessions/${row.id}/${action}`)
    toast.success(`${label}成功：${row.title}`)
    load()
  } catch (e) {
    toast.error(e.message || `${label}失败`)
  } finally {
    busy.value = false
  }
}

/**
 * 批量操作。三个都走同一套逻辑：调接口 → 用返回的汇总提示。
 *
 * 后端**不做「一个失败全回滚」**，所以这里必须把失败数如实说出来，
 * 否则管理员看到「成功」会以为全都生效了。
 */
async function batch(action, label, confirmText) {
  const ok = await confirm({ title: label, message: confirmText, confirmText: '确定', danger: true })
  if (!ok) return
  busy.value = true
  try {
    const result = await http.post(`/admin/sessions/${action}`)
    const msg = `${label}：共 ${result.total} 个，成功 ${result.succeeded}，失败 ${result.failed}`
    if (result.failed) {
      toast.warning(`${msg}。失败原因：${result.failures.map((f) => f.reason).join('；')}`)
    } else {
      toast.success(msg)
    }
    load()
  } catch (e) {
    toast.error(e.message || `${label}失败`)
  } finally {
    busy.value = false
  }
}

// ── 在线名单 / 踢人 ────────────────────────────────────────
const onlineOpen = ref(false)
const onlineLoading = ref(false)
const onlineUsers = ref([])
const onlineSession = ref(null)

async function openOnline(row) {
  onlineSession.value = row
  onlineOpen.value = true
  onlineLoading.value = true
  onlineUsers.value = []
  try {
    const data = await http.get(`/admin/sessions/${row.id}/online`)
    onlineUsers.value = data.list || []
  } catch (e) {
    toast.error(e.message || '读取在线名单失败')
  } finally {
    onlineLoading.value = false
  }
}

async function kick(user) {
  const reason = await prompt({
    title: '移出课堂',
    message: `把「${user.nickname || user.userId}」移出课堂？\n\n理由会显示给他看。`,
    inputLabel: '理由',
    defaultValue: '管理员移出课堂',
    confirmText: '移出',
  })
  if (reason === null) return
  try {
    await http.post(`/admin/sessions/${onlineSession.value.id}/kick`, {
      userId: user.userId,
      reason,
    })
    toast.success('已移出')
    openOnline(onlineSession.value)
  } catch (e) {
    toast.error(e.message || '移出失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="page__title">课堂管理</h1>
      <div class="page__batch">
        <FcButton
          variant="secondary"
          size="sm"
          :disabled="busy"
          @click="
            batch(
              'pause-all',
              '批量暂停',
              '暂停所有进行中的课堂？\n\n注意：这只会改变状态并通知学生端，「不会切断老师的画面」——媒体流是点对点直连的，需要老师端自行停止共享。',
            )
          "
        >
          暂停所有课堂
        </FcButton>
        <FcButton
          variant="secondary"
          size="sm"
          :disabled="busy"
          @click="batch('resume-all', '批量恢复', '恢复所有已暂停的课堂？')"
        >
          全部恢复
        </FcButton>
        <FcButton
          variant="danger"
          size="sm"
          :disabled="busy"
          @click="
            batch('end-all', '批量下课', '强制结束所有进行中的课堂？\n\n学生会立即看到「已结束」，且无法再翻页。')
          "
        >
          全部下课
        </FcButton>
      </div>
    </header>

    <FcCard padding="none">
      <div class="filters">
        <input
          v-model="filters.keyword"
          class="filters__input"
          placeholder="搜索课堂名称…"
          @keyup.enter="search"
        />
        <select v-model="filters.status" class="filters__select" @change="search">
          <option value="">全部状态</option>
          <option value="NOT_STARTED">未开始</option>
          <option value="LIVE">直播中</option>
          <option value="PAUSED">已暂停</option>
          <option value="ENDED">已结束</option>
        </select>
        <FcButton variant="secondary" size="sm" @click="search">查询</FcButton>
      </div>

      <p v-if="error" class="page__err" role="alert">{{ error }}</p>

      <FcTable :columns="columns" :rows="rows" :loading="loading" empty-text="没有匹配的课堂">
        <template #cell-title="{ row }">
          <span class="name">{{ row.title || '未命名课堂' }}</span>
        </template>

        <template #cell-coursewareName="{ row }">
          <span class="dim">{{ row.coursewareName || '—' }}</span>
        </template>

        <template #cell-teacherName="{ row }">
          {{ row.teacherName || '—' }}
        </template>

        <template #cell-status="{ row }">
          <FcTag :type="STATUS_TAG[row.status] || 'default'" size="sm">
            {{ STATUS_TEXT[row.status] || row.status }}
          </FcTag>
        </template>

        <template #cell-currentPage="{ row }">
          <span class="dim">{{ row.currentPage ?? '—' }}</span>
        </template>

        <template #cell-ops="{ row }">
          <div class="ops">
            <button
              v-if="row.status !== 'PAUSED' && row.status !== 'ENDED'"
              class="ops__btn"
              type="button"
              :disabled="busy"
              @click="rowOp(row, 'pause', '暂停')"
            >
              暂停
            </button>
            <button
              v-if="row.status === 'PAUSED'"
              class="ops__btn"
              type="button"
              :disabled="busy"
              @click="rowOp(row, 'resume', '恢复')"
            >
              恢复
            </button>
            <button
              v-if="row.status !== 'ENDED'"
              class="ops__btn ops__btn--danger"
              type="button"
              :disabled="busy"
              @click="
                rowOp(row, 'end', '强制下课', `强制结束「${row.title}」？\n\n学生会立即看到「已结束」，且无法再翻页。`)
              "
            >
              强制下课
            </button>
            <button
              v-if="row.status !== 'ENDED'"
              class="ops__btn"
              type="button"
              @click="openOnline(row)"
            >
              在线名单
            </button>
            <!-- 已结束的课给「回顾」入口（问题点 9）。
                 管理员本来就有权限看课堂记录，之前只是**没有任何一条路通向它**，
                 所以「下课之后看不到」的观感一直存在。 -->
            <RouterLink
              v-if="row.status === 'ENDED'"
              class="ops__btn"
              :to="{ name: 'session-record', params: { sessionId: row.id } }"
            >
              回顾
            </RouterLink>
          </div>
        </template>
      </FcTable>
    </FcCard>

    <div class="pager">
      <FcButton variant="secondary" size="sm" :disabled="page <= 1" @click="goPage(-1)">
        上一页
      </FcButton>
      <span class="pager__info">第 {{ page }} 页 · 共 {{ total }} 条</span>
      <FcButton
        variant="secondary"
        size="sm"
        :disabled="page >= Math.ceil(total / size)"
        @click="goPage(1)"
      >
        下一页
      </FcButton>
    </div>

    <FcModal v-model="onlineOpen" :title="`在线名单 · ${onlineSession?.title || ''}`" width="440px">
      <FcLoading v-if="onlineLoading" text="读取中…" />

      <p v-else-if="!onlineUsers.length" class="empty">
        当前没有人在线。
        <br />
        <span class="dim">（名单来自服务端的内存连接登记表，不含已断开的人）</span>
      </p>

      <ul v-else class="online">
        <li v-for="u in onlineUsers" :key="u.userId" class="online__item">
          <span class="online__name">{{ u.nickname || `用户 #${u.userId}` }}</span>
          <FcTag :type="u.role === 'TEACHER' ? 'primary' : 'default'" size="sm">
            {{ u.role === 'TEACHER' ? '老师' : '学生' }}
          </FcTag>
          <FcButton size="sm" variant="danger" @click="kick(u)">移出</FcButton>
        </li>
      </ul>
    </FcModal>
  </div>
</template>

<style scoped>
.page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--fc-space-4);
  flex-wrap: wrap;
  margin-bottom: var(--fc-space-5);
}

.page__title {
  font-size: var(--fc-font-xl);
  color: var(--fc-text);
}

.page__batch {
  display: flex;
  gap: var(--fc-space-2);
  flex-wrap: wrap;
}

.filters {
  display: flex;
  gap: var(--fc-space-2);
  padding: var(--fc-space-3) var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
}

.filters__input {
  flex: 1;
  min-width: 160px;
  height: 30px;
  padding: 0 var(--fc-space-3);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
}

.filters__select {
  height: 30px;
  padding: 0 var(--fc-space-2);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
  background: var(--fc-bg-panel);
}

.filters__input:focus,
.filters__select:focus {
  outline: none;
  border-color: var(--fc-primary);
}

.name {
  color: var(--fc-text);
}

.dim {
  color: var(--fc-text-faint);
  font-size: var(--fc-font-xs);
}

.ops {
  display: flex;
  gap: var(--fc-space-2);
  flex-wrap: wrap;
}

.ops__btn {
  font-size: var(--fc-font-xs);
  color: var(--fc-primary);
  padding: 1px 4px;
  border-radius: var(--fc-radius-sm);
}
.ops__btn:hover:not(:disabled) {
  background: var(--fc-primary-bg);
}
/* 「回顾」是个 <a>（RouterLink），要显式去掉下划线，否则与旁边的按钮不像一套 */
a.ops__btn {
  text-decoration: none;
  cursor: pointer;
}

.ops__btn--danger {
  color: var(--fc-danger);
}
.ops__btn--danger:hover:not(:disabled) {
  background: var(--fc-danger-bg);
}
.ops__btn:disabled {
  opacity: 0.5;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--fc-space-4);
  margin-top: var(--fc-space-4);
}

.pager__info {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-faint);
}

.online {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
}

.online__item {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  padding: var(--fc-space-2) var(--fc-space-3);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
}

.online__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--fc-font-sm);
}

.empty {
  text-align: center;
  color: var(--fc-text-muted);
  font-size: var(--fc-font-sm);
  line-height: 1.8;
  padding: var(--fc-space-5) 0;
}

.page__err {
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
  padding: var(--fc-space-3) var(--fc-space-4) 0;
  margin: 0;
}
</style>
