<script setup>
// 管理端概览。只读 + 一个「暂停所有课堂」的快捷操作。
import { computed, onMounted, ref } from 'vue'
import http from '@/api/http'
import { useToast } from '@/composables/useToast'
import { FcButton, FcCard, FcLoading } from '@/components/base'

const toast = useToast()

const users = ref(null)
const sessions = ref(null)
const storage = ref(null)
const loading = ref(true)
const error = ref('')
const pausingAll = ref(false)
const lastBatch = ref(null)

const liveCount = computed(
  () => (sessions.value?.list || []).filter((s) => s.status === 'LIVE').length,
)
const pausedCount = computed(
  () => (sessions.value?.list || []).filter((s) => s.status === 'PAUSED').length,
)

/** 字节 → 人能读的写法。管理端最常问的就是「存储用了多少」。 */
function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 三个都并发拉，任一失败由外层统一提示
    const [u, s, st] = await Promise.all([
      http.get('/admin/users', { params: { size: 1 } }),
      http.get('/admin/sessions', { params: { size: 100 } }),
      http.get('/admin/storage/overview'),
    ])
    users.value = u
    sessions.value = s
    storage.value = st
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

/**
 * 暂停所有进行中的课堂。
 *
 * <p>二次确认里必须写明「画面不会立刻停」——这是 P2P 架构的固有边界。
 * 不写清楚的话，管理员点完发现画面还在，会以为功能坏了，然后反复点。
 */
async function pauseAll() {
  const ok = window.confirm(
    '暂停所有进行中的课堂？\n\n' +
      '会发生：课堂状态变为「已暂停」，学生端弹出遮罩，讨论区与 AI 提问被禁用。\n' +
      '不会发生：老师的画面与声音「不会被服务端切断」——媒体流是点对点直连的，\n' +
      '需要老师端收到提示后自行停止共享。',
  )
  if (!ok) return

  pausingAll.value = true
  try {
    lastBatch.value = await http.post('/admin/sessions/pause-all')
    toast.success(
      `已暂停 ${lastBatch.value.succeeded} / ${lastBatch.value.total} 个课堂` +
        (lastBatch.value.failed ? `，${lastBatch.value.failed} 个失败` : ''),
    )
    await load()
  } catch (e) {
    toast.error(e.message || '批量暂停失败')
  } finally {
    pausingAll.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="dash">
    <header class="dash__head">
      <h1 class="dash__title">概览</h1>
      <FcButton variant="secondary" size="sm" @click="load">刷新</FcButton>
    </header>

    <FcLoading v-if="loading" text="加载概览…" />
    <p v-else-if="error" class="dash__err" role="alert">{{ error }}</p>

    <template v-else>
      <div class="dash__grid">
        <FcCard>
          <p class="stat__value">{{ users?.total ?? 0 }}</p>
          <p class="stat__label">账号总数</p>
        </FcCard>

        <FcCard>
          <p class="stat__value">{{ sessions?.total ?? 0 }}</p>
          <p class="stat__label">课堂总数</p>
        </FcCard>

        <FcCard>
          <p class="stat__value" :class="{ 'stat__value--live': liveCount }">{{ liveCount }}</p>
          <p class="stat__label">正在直播</p>
        </FcCard>

        <FcCard>
          <p class="stat__value">{{ pausedCount }}</p>
          <p class="stat__label">已暂停</p>
        </FcCard>

        <FcCard>
          <p class="stat__value">{{ formatBytes(storage?.totalBytes) }}</p>
          <p class="stat__label">
            存储占用（课件 {{ storage?.coursewareCount ?? 0 }} 份）
          </p>
        </FcCard>
      </div>

      <FcCard title="快捷操作" subtitle="批量操作会逐个执行并汇总结果，不会因为其中一个失败而全部回滚">
        <div class="actions">
          <FcButton :loading="pausingAll" @click="pauseAll">暂停所有课堂</FcButton>
          <router-link class="actions__link" to="/admin/sessions">去课堂管理 →</router-link>
        </div>

        <p v-if="lastBatch" class="batch">
          上次批量操作：共 {{ lastBatch.total }} 个，成功 {{ lastBatch.succeeded }}，失败
          {{ lastBatch.failed }}
          <span v-if="lastBatch.failures?.length" class="batch__fail">
            （{{ lastBatch.failures.map((f) => `${f.title}：${f.reason}`).join('；') }}）
          </span>
        </p>
      </FcCard>

      <p class="dash__note">
        ⚠️ 「暂停课堂」不会切断画面。媒体流是老师与学生点对点直连的，服务端不经手——
        要真正停画面，需要老师端自行停止共享。
      </p>
    </template>
  </div>
</template>

<style scoped>
.dash__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--fc-space-5);
}

.dash__title {
  font-size: var(--fc-font-xl);
  color: var(--fc-text);
}

.dash__grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: var(--fc-space-4);
  margin-bottom: var(--fc-space-5);
}

.stat__value {
  margin: 0;
  font-size: var(--fc-font-2xl);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-primary);
  font-variant-numeric: tabular-nums;
}

.stat__value--live {
  color: var(--fc-danger);
}

.stat__label {
  margin: var(--fc-space-1) 0 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.actions {
  display: flex;
  align-items: center;
  gap: var(--fc-space-4);
}

.actions__link {
  font-size: var(--fc-font-sm);
}

.batch {
  margin: var(--fc-space-3) 0 0;
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
}

.batch__fail {
  color: var(--fc-warning-text);
}

.dash__note {
  margin-top: var(--fc-space-5);
  padding: var(--fc-space-3) var(--fc-space-4);
  border-radius: var(--fc-radius);
  background: var(--fc-warning-bg);
  border: 1px solid var(--fc-warning-border);
  color: var(--fc-warning-text);
  font-size: var(--fc-font-sm);
  line-height: 1.7;
}

.dash__err {
  color: var(--fc-danger);
}
</style>
