<script setup>
// 管理端：操作日志（对外文案；后端类名仍叫 AdminAudit*，不改是刻意的 ——
// 改类名/表名要牵动迁移脚本与接口契约，收益为零）。只做「查」，写入统一走后端 AdminAuditService.record。
import { onMounted, reactive, ref } from 'vue'
import http from '@/api/http'
import { FcButton, FcCard, FcTable } from '@/components/base'

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 30
const loading = ref(false)
const error = ref('')

const filters = reactive({ action: '', adminId: '' })

/** 动作码 → 中文。与后端 AdminAuditService 的常量一一对应。 */
const ACTION_TEXT = {
  USER_CREATE: '新建账号',
  USER_DISABLE: '禁用账号',
  USER_ENABLE: '启用账号',
  USER_RESET_PWD: '重置密码',
  USER_CHANGE_ROLE: '修改角色',
  USER_DELETE: '删除账号',
  SESSION_FORCE_END: '强制下课',
  SESSION_PAUSE: '暂停课堂',
  SESSION_RESUME: '恢复课堂',
  SESSION_PAUSE_ALL: '批量暂停',
  SESSION_RESUME_ALL: '批量恢复',
  SESSION_END_ALL: '批量下课',
  SESSION_KICK: '移出学生',
  COURSEWARE_DELETE: '删除课件',
  COURSEWARE_REPARSE: '重新解析',
  STORAGE_ORPHAN_CLEAN: '清理孤立文件',
}

const columns = [
  { key: 'createdAt', title: '时间', width: '150px' },
  { key: 'adminName', title: '操作人', width: '110px' },
  { key: 'action', title: '动作', width: '120px' },
  { key: 'target', title: '对象', width: '110px' },
  { key: 'detail', title: '详情' },
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = { page: page.value, size }
    if (filters.action) params.action = filters.action
    if (filters.adminId.trim()) params.adminId = filters.adminId.trim()
    const data = await http.get('/admin/audit-logs', { params })
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

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 19) : ''
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="page__title">操作日志</h1>
      <FcButton variant="secondary" size="sm" @click="load">刷新</FcButton>
    </header>

    <FcCard padding="none">
      <div class="filters">
        <select v-model="filters.action" class="filters__select" @change="search">
          <option value="">全部动作</option>
          <option v-for="(label, code) in ACTION_TEXT" :key="code" :value="code">
            {{ label }}（{{ code }}）
          </option>
        </select>
        <input
          v-model="filters.adminId"
          class="filters__input"
          placeholder="按操作人 ID 筛选…"
          @keyup.enter="search"
        />
        <FcButton variant="secondary" size="sm" @click="search">查询</FcButton>
      </div>

      <p v-if="error" class="page__err" role="alert">{{ error }}</p>

      <FcTable :columns="columns" :rows="rows" :loading="loading" empty-text="还没有操作记录">
        <template #cell-createdAt="{ row }">
          <span class="dim">{{ formatTime(row.createdAt) }}</span>
        </template>

        <template #cell-adminName="{ row }">
          {{ row.adminName || `#${row.adminId}` }}
        </template>

        <template #cell-action="{ row }">
          <span class="action">{{ ACTION_TEXT[row.action] || row.action }}</span>
          <span class="dim action__code">{{ row.action }}</span>
        </template>

        <template #cell-target="{ row }">
          <span class="dim">
            {{ row.targetType || '—' }}<template v-if="row.targetId"> #{{ row.targetId }}</template>
          </span>
        </template>

        <template #cell-detail="{ row }">
          <span class="detail">{{ row.detail || '—' }}</span>
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

    <p class="note">
      操作日志只增不改，也没有删除入口——它的价值就在于「当时发生了什么」这个事实本身。
      按需求，这里只做「写 + 查」，不配图表与统计。
    </p>
  </div>
</template>

<style scoped>
.page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--fc-space-5);
}

.page__title {
  font-size: var(--fc-font-xl);
  color: var(--fc-text);
}

.filters {
  display: flex;
  gap: var(--fc-space-2);
  padding: var(--fc-space-3) var(--fc-space-4);
  border-bottom: 1px solid var(--fc-border);
}

.filters__select {
  height: 30px;
  padding: 0 var(--fc-space-2);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
  background: var(--fc-bg-panel);
  max-width: 260px;
}

.filters__input {
  flex: 1;
  min-width: 140px;
  height: 30px;
  padding: 0 var(--fc-space-3);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
}

.filters__select:focus,
.filters__input:focus {
  outline: none;
  border-color: var(--fc-primary);
}

.action {
  display: block;
  font-size: var(--fc-font-sm);
  color: var(--fc-text);
}

.action__code {
  font-family: var(--fc-font-mono);
  font-size: 11px;
}

.detail {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-muted);
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.dim {
  color: var(--fc-text-faint);
  font-size: var(--fc-font-xs);
  font-variant-numeric: tabular-nums;
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

.note {
  margin-top: var(--fc-space-4);
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
  line-height: 1.8;
}

.page__err {
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
  padding: var(--fc-space-3) var(--fc-space-4) 0;
  margin: 0;
}
</style>
