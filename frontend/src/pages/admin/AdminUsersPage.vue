<script setup>
// 管理端：账号管理。
import { onMounted, reactive, ref } from 'vue'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { FcButton, FcCard, FcEmptyState, FcInput, FcModal, FcTable, FcTag } from '@/components/base'

const auth = useAuthStore()
const toast = useToast()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const error = ref('')

const filters = reactive({ keyword: '', role: '', disabled: '' })

const columns = [
  { key: 'username', title: '用户名' },
  { key: 'nickname', title: '昵称' },
  { key: 'role', title: '角色', width: '90px' },
  { key: 'disabled', title: '状态', width: '90px' },
  { key: 'createdAt', title: '创建时间', width: '150px' },
  { key: 'ops', title: '操作', width: '260px' },
]

const ROLE_TEXT = { TEACHER: '教师', STUDENT: '学生', ADMIN: '管理员' }
const ROLE_TAG = { TEACHER: 'primary', STUDENT: 'default', ADMIN: 'danger' }

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = { page: page.value, size }
    if (filters.keyword.trim()) params.keyword = filters.keyword.trim()
    if (filters.role) params.role = filters.role
    if (filters.disabled !== '') params.disabled = filters.disabled === 'true'

    const data = await http.get('/admin/users', { params })
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
  if (next < 1) return
  if (next > Math.ceil(total.value / size)) return
  page.value = next
  load()
}

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : ''
}

// ── 新建账号 ───────────────────────────────────────────────
const createOpen = ref(false)
const creating = ref(false)
const form = reactive({ username: '', password: '', role: 'STUDENT', nickname: '' })
const formError = ref('')

function openCreate() {
  Object.assign(form, { username: '', password: '', role: 'STUDENT', nickname: '' })
  formError.value = ''
  createOpen.value = true
}

async function submitCreate() {
  formError.value = ''
  if (!form.username.trim() || !form.password) {
    formError.value = '用户名与密码都要填'
    return
  }
  creating.value = true
  try {
    await http.post('/admin/users', {
      username: form.username.trim(),
      password: form.password,
      role: form.role,
      nickname: form.nickname.trim() || null,
    })
    toast.success('账号已创建')
    createOpen.value = false
    search()
  } catch (e) {
    formError.value = e.message || '创建失败'
  } finally {
    creating.value = false
  }
}

// ── 行操作 ────────────────────────────────────────────────

async function toggleDisabled(row) {
  const next = !row.disabled
  const label = next ? '禁用' : '启用'
  if (next && !window.confirm(`禁用「${row.username}」？\n\n他将立即无法登录（已签发的令牌也会被作废），账号与数据都保留，随时可以启用回来。`)) {
    return
  }
  try {
    await http.put(`/admin/users/${row.id}/status`, { disabled: next })
    toast.success(`已${label} ${row.username}`)
    load()
  } catch (e) {
    toast.error(e.message || `${label}失败`)
  }
}

async function resetPassword(row) {
  const pwd = window.prompt(`给「${row.username}」设置新密码（至少 6 位）：`)
  if (!pwd) return
  try {
    await http.put(`/admin/users/${row.id}/password`, { newPassword: pwd })
    toast.success('密码已重置，该账号需要重新登录')
  } catch (e) {
    toast.error(e.message || '重置失败')
  }
}

async function changeRole(row) {
  const role = window.prompt(
    `修改「${row.username}」的角色。\n可填：TEACHER / STUDENT / ADMIN\n当前：${row.role}`,
    row.role,
  )
  if (!role || role === row.role) return
  try {
    await http.put(`/admin/users/${row.id}/role`, { role: role.trim().toUpperCase() })
    toast.success('角色已修改，该账号需要重新登录')
    load()
  } catch (e) {
    toast.error(e.message || '修改失败')
  }
}

async function removeUser(row) {
  if (!window.confirm(`删除「${row.username}」？\n\n这是软删除：他的课件、问答与发言记录都会保留，只是不再出现在列表里、也无法登录。`)) {
    return
  }
  try {
    await http.delete(`/admin/users/${row.id}`)
    toast.success('已删除')
    load()
  } catch (e) {
    toast.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="page__title">账号管理</h1>
      <FcButton @click="openCreate">＋ 新建账号</FcButton>
    </header>

    <FcCard padding="none">
      <div class="filters">
        <input
          v-model="filters.keyword"
          class="filters__input"
          placeholder="搜索用户名或昵称…"
          @keyup.enter="search"
        />
        <select v-model="filters.role" class="filters__select" @change="search">
          <option value="">全部角色</option>
          <option value="TEACHER">教师</option>
          <option value="STUDENT">学生</option>
          <option value="ADMIN">管理员</option>
        </select>
        <select v-model="filters.disabled" class="filters__select" @change="search">
          <option value="">全部状态</option>
          <option value="false">正常</option>
          <option value="true">已禁用</option>
        </select>
        <FcButton variant="secondary" size="sm" @click="search">查询</FcButton>
      </div>

      <p v-if="error" class="page__err" role="alert">{{ error }}</p>

      <FcTable :columns="columns" :rows="rows" :loading="loading" empty-text="没有匹配的账号">
        <template #cell-username="{ row }">
          <span class="mono">{{ row.username }}</span>
        </template>

        <template #cell-nickname="{ row }">{{ row.nickname || '—' }}</template>

        <template #cell-role="{ row }">
          <FcTag :type="ROLE_TAG[row.role] || 'default'" size="sm">
            {{ ROLE_TEXT[row.role] || row.role }}
          </FcTag>
        </template>

        <template #cell-disabled="{ row }">
          <FcTag :type="row.disabled ? 'danger' : 'success'" size="sm">
            {{ row.disabled ? '已禁用' : '正常' }}
          </FcTag>
        </template>

        <template #cell-createdAt="{ row }">
          <span class="dim">{{ formatTime(row.createdAt) }}</span>
        </template>

        <template #cell-ops="{ row }">
          <div class="ops">
            <button class="ops__btn" type="button" @click="toggleDisabled(row)">
              {{ row.disabled ? '启用' : '禁用' }}
            </button>
            <button class="ops__btn" type="button" @click="resetPassword(row)">重置密码</button>
            <button class="ops__btn" type="button" @click="changeRole(row)">改角色</button>
            <button class="ops__btn ops__btn--danger" type="button" @click="removeUser(row)">
              删除
            </button>
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

    <FcModal v-model="createOpen" title="新建账号" width="420px">
      <div class="form">
        <FcInput v-model="form.username" label="用户名" placeholder="字母、数字、下划线" />
        <FcInput v-model="form.password" label="初始密码" type="password" placeholder="至少 6 位" />
        <FcInput v-model="form.nickname" label="昵称" placeholder="可留空" />
        <label class="form__label">
          角色
          <select v-model="form.role" class="form__select">
            <option value="STUDENT">学生</option>
            <option value="TEACHER">教师</option>
            <option value="ADMIN">管理员</option>
          </select>
        </label>
        <p v-if="formError" class="form__err" role="alert">{{ formError }}</p>
      </div>

      <template #footer>
        <FcButton variant="secondary" @click="createOpen = false">取消</FcButton>
        <FcButton :loading="creating" @click="submitCreate">创建</FcButton>
      </template>
    </FcModal>
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
  flex-wrap: wrap;
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
.filters__input:focus,
.filters__select:focus {
  outline: none;
  border-color: var(--fc-primary);
}

.filters__select {
  height: 30px;
  padding: 0 var(--fc-space-2);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
  background: var(--fc-bg-panel);
}

.mono {
  font-family: var(--fc-font-mono);
  font-size: var(--fc-font-xs);
}

.dim {
  color: var(--fc-text-faint);
  font-size: var(--fc-font-xs);
  font-variant-numeric: tabular-nums;
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
.ops__btn:hover {
  background: var(--fc-primary-bg);
}
.ops__btn--danger {
  color: var(--fc-danger);
}
.ops__btn--danger:hover {
  background: var(--fc-danger-bg);
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

.form {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
}

.form__label {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-1);
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
}

.form__select {
  height: 32px;
  padding: 0 var(--fc-space-2);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  background: var(--fc-bg-panel);
}

.form__err {
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
  margin: 0;
}

.page__err {
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
  padding: var(--fc-space-3) var(--fc-space-4) 0;
  margin: 0;
}
</style>
