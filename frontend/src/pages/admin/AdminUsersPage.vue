<script setup>
// 管理端：账号管理。
import { onMounted, reactive, ref } from 'vue'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useToast } from '@/composables/useToast'
import { confirm, prompt } from '@/composables/useConfirm'
import { useBulkDelete } from '@/composables/useBulkDelete'
import { useUserFormRules } from '@/composables/useUserFormRules'
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
  // 勾选列没有标题（表头放的是「全选」复选框，见 #header-select）
  { key: 'select', title: '', width: '44px' },
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
  // 换页 / 改筛选之后行会整批换掉，旧的勾选必须清空 ——
  // 否则「已选 3 项」里混着上一页的行，批量删除会删掉用户根本没看见的账号。
  clearPicked()
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
/** 服务端返回的整体错误（如「用户名已存在」）。字段级错误走 `errors`。 */
const formError = ref('')

/**
 * 字段级校验：哪个框没填就把红字挂在哪一行，而不是只弹一条笼统提示。
 * 昵称**不参与必填校验**（后端本来就是可选的），所以只列另外两个字段。
 */
const { errors, checkAll, clearAll, clear } = useUserFormRules(['username', 'password', 'nickname'])

function openCreate() {
  Object.assign(form, { username: '', password: '', role: 'STUDENT', nickname: '' })
  formError.value = ''
  clearAll()
  createOpen.value = true
}

async function submitCreate() {
  formError.value = ''
  // 先跑字段校验：用户名、密码各自标红；昵称只查长度，不查空
  if (!checkAll(form)) {
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
  if (next) {
    const ok = await confirm({
      title: '禁用账号',
      message: `禁用「${row.username}」？\n\n他将立即无法登录（已签发的令牌也会被作废），账号与数据都保留，随时可以启用回来。`,
      confirmText: '禁用',
      danger: true,
    })
    if (!ok) return
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
  const pwd = await prompt({
    title: '重置密码',
    message: `给「${row.username}」设置新密码。\n重置后该账号已签发的令牌会立即失效，需要重新登录。`,
    inputLabel: '新密码',
    placeholder: '至少 6 位',
    // 与后端 PASSWORD_MIN 一致；不足时留在弹窗里标红，不会静默失败
    minLength: 6,
    confirmText: '重置',
  })
  if (pwd === null) return
  try {
    await http.put(`/admin/users/${row.id}/password`, { newPassword: pwd })
    toast.success('密码已重置，该账号需要重新登录')
  } catch (e) {
    toast.error(e.message || '重置失败')
  }
}

async function changeRole(row) {
  const role = await prompt({
    title: '修改角色',
    message: `修改「${row.username}」的角色。\n可填：TEACHER / STUDENT / ADMIN\n当前：${row.role}`,
    inputLabel: '角色',
    defaultValue: row.role,
    confirmText: '修改',
  })
  if (role === null || !role || role === row.role) return
  try {
    await http.put(`/admin/users/${row.id}/role`, { role: role.trim().toUpperCase() })
    toast.success('角色已修改，该账号需要重新登录')
    load()
  } catch (e) {
    toast.error(e.message || '修改失败')
  }
}

/**
 * 真正的那一次删除请求。单条删除与批量删除**共用这一个函数体**，
 * 两条路走同一个后端接口 —— 不会出现「单个能删、批量删不掉」这种分歧。
 */
function deleteOne(row) {
  return http.delete(`/admin/users/${row.id}`)
}

async function removeUser(row) {
  const ok = await confirm({
    title: '删除账号',
    message: `删除「${row.username}」？\n\n这是软删除：他的课件、问答与发言记录都会保留，只是不再出现在列表里、也无法登录。`,
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await deleteOne(row)
    toast.success('已删除')
    load()
  } catch (e) {
    toast.error(e.message || '删除失败')
  }
}

const {
  picked,
  pickedCount,
  allPicked,
  removing,
  togglePick,
  toggleAll,
  clearPicked,
  removePicked,
} = useBulkDelete({
  rows,
  idOf: (row) => row.id,
  nameOf: (row) => row.username,
  removeOne: deleteOne,
  noun: '个账号',
  note: '这是软删除：他们的课件、问答与发言记录都会保留，只是不再出现在列表里、也无法登录。',
  // 后端本来就会拒绝「删自己」，但让用户先勾上、点了才被拒，体验是差的 ——
  // 这里直接让那一行选不了。
  canPick: (row) => row.id !== auth.user?.id,
  onDone: load,
})

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="page__title">账号管理</h1>
      <div class="page__ops">
        <!-- 没勾选时不显示：一个永远是灰的按钮只是噪音。
             按钮上带条数，避免"我到底选了几个"要靠自己数。 -->
        <FcButton v-if="pickedCount" variant="danger" :loading="removing" @click="removePicked">
          删除选中（{{ pickedCount }}）
        </FcButton>
        <FcButton @click="openCreate">＋ 新建账号</FcButton>
      </div>
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
        <template #header-select>
          <input
            type="checkbox"
            class="row-pick"
            :checked="allPicked"
            :aria-label="allPicked ? '取消全选本页' : '全选本页'"
            @change="toggleAll"
          />
        </template>

        <template #cell-select="{ row }">
          <!-- 自己那一行不给勾：后端本来就会拒绝删自己，
               但让用户先选上、点了才被拒，体验是差的。 -->
          <input
            type="checkbox"
            class="row-pick"
            :checked="picked.has(row.id)"
            :disabled="row.id === auth.user?.id"
            :title="row.id === auth.user?.id ? '这是你自己的账号，不能删除' : undefined"
            :aria-label="`选择 ${row.username}`"
            @change="togglePick(row.id)"
          />
        </template>

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
        <!--
          :error 绑的是字段级校验结果：用户名、密码为空或长度不对时，
          红框与红字直接挂在对应那一行，而不是底部一条笼统提示。
          @update:model-value 里清错——用户已经在改了，旧的红字就该消失了。
        -->
        <FcInput
          v-model="form.username"
          label="用户名"
          placeholder="字母、数字、下划线"
          :error="errors.username"
          @update:model-value="clear('username')"
        />
        <FcInput
          v-model="form.password"
          label="初始密码"
          type="password"
          placeholder="至少 6 位"
          :error="errors.password"
          @update:model-value="clear('password')"
        />
        <!-- 昵称是选填的，只查长度，不会因为留空而标红 -->
        <FcInput
          v-model="form.nickname"
          label="昵称"
          placeholder="可留空"
          :error="errors.nickname"
          @update:model-value="clear('nickname')"
        />
        <label class="form__label">
          角色
          <select v-model="form.role" class="form__select">
            <option value="STUDENT">学生</option>
            <option value="TEACHER">教师</option>
            <option value="ADMIN">管理员</option>
          </select>
        </label>
        <!-- 这里只放服务端返回的错误（如「用户名已存在」），字段级错误在上面各行 -->
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

/* 标题右侧的操作区：批量删除按钮出现时，两个按钮要并排且留间距 */
.page__ops {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
}

/* 表格里行的勾选框。浏览器默认只有 13px 左右，在这个密度的表格里偏小；
   给到 16px 更好点，也让行高对齐更稳。accent-color 跟随品牌色。
   三张管理端表格统一用 .row-pick 这个类名（.pick 在 ClassGroupManager 里
   已被「班主任下拉框」占用，所以不能直接用那个名字）。 */
.row-pick {
  width: 16px;
  height: 16px;
  accent-color: var(--fc-primary);
  cursor: pointer;
  vertical-align: middle;
}
.row-pick:disabled {
  cursor: not-allowed;
  opacity: 0.4;
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
