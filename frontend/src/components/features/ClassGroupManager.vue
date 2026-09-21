<script setup>
// 班级管理界面，**教师端与管理端共用这一个组件**。
//
// 为什么共用：两边要做的事几乎一样（列班级、建班、加人、移人、看这个班上过哪些课），
// 差别只有三点：接口前缀、能不能跨教师、要不要显示「所属教师」列。
// 写两份的话，改一处逻辑就得记得同步另一处，而漏掉的那次往往正是安全相关的那次
// （后端 ClassGroupService 也是同样的取舍，那边用 isAdmin 参数分叉）。
//
// ⚠ 前端这里的 admin 只是**体验层**：真正的边界在后端 ——
// /api/admin/** 在 SecurityConfig 上限 ADMIN，而教师端接口的归属校验
// 由 ClassGroupService.requireOwnedGroup 兜底。前端不做安全判断，也不该做。
import { computed, onMounted, reactive, ref } from 'vue'
import http from '@/api/http'
import { useToast } from '@/composables/useToast'
import { confirm } from '@/composables/useConfirm'
import { FcButton, FcCard, FcEmptyState, FcInput, FcLoading, FcModal, FcTable } from '@/components/base'

const props = defineProps({
  /** true = 管理端（跨教师、显示所属教师、可按教师筛选） */
  admin: { type: Boolean, default: false },
})

/** 两个端的接口前缀。管理端那份自动被 /api/admin/** → hasRole('ADMIN') 保护。 */
const API = props.admin ? '/admin/class-groups' : '/class-groups'

const toast = useToast()

// ── 班级列表 ───────────────────────────────────────────────
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const keyword = ref('')
/** 仅管理端用：按教师筛选 */
const teacherFilter = ref('')
const loading = ref(false)
const error = ref('')

const columns = computed(() => {
  const base = [{ key: 'name', title: '班级' }, { key: 'description', title: '备注' }]
  if (props.admin) {
    base.push({ key: 'teacherName', title: '所属教师', width: '120px' })
  }
  base.push(
    { key: 'memberCount', title: '人数', width: '80px' },
    { key: 'sessionCount', title: '已开课', width: '90px' },
    { key: 'createdAt', title: '创建时间', width: '150px' },
    { key: 'ops', title: '操作', width: '190px' },
  )
  return base
})

function formatTime(value) {
  return value ? String(value).replace('T', ' ').slice(0, 16) : ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const params = { page: page.value, size }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (props.admin && String(teacherFilter.value).trim()) {
      params.teacherId = String(teacherFilter.value).trim()
    }
    const data = await http.get(API, { params })
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

// ── 新建 / 改名 ────────────────────────────────────────────
const formOpen = ref(false)
const saving = ref(false)
/** 非空表示当前是「改名」而不是「新建」 */
const editingId = ref(null)
const form = reactive({ name: '', description: '' })
const formError = ref('')

function openCreate() {
  editingId.value = null
  form.name = ''
  form.description = ''
  formError.value = ''
  formOpen.value = true
}

function openEdit(row) {
  editingId.value = row.id
  form.name = row.name
  form.description = row.description || ''
  formError.value = ''
  formOpen.value = true
}

async function submitForm() {
  formError.value = ''
  if (!form.name.trim()) {
    formError.value = '班级名不能为空'
    return
  }
  saving.value = true
  try {
    const body = { name: form.name.trim(), description: form.description.trim() || null }
    if (editingId.value) {
      await http.put(`${API}/${editingId.value}`, body)
      toast.success('已保存')
    } else {
      await http.post(API, body)
      toast.success('班级已创建')
    }
    formOpen.value = false
    load()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function removeGroup(row) {
  const ok = await confirm({
    title: '删除班级',
    message:
      `删除班级「${row.name}」？\n\n` +
      '这是软删除：班里的学生记录与**已经上过的课**都保留。' +
      '学生照常能回顾以前上过的课 —— 可见性在开课那一刻就定下来了，与班级是否还在无关。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  try {
    await http.delete(`${API}/${row.id}`)
    toast.success('已删除')
    load()
  } catch (e) {
    toast.error(e.message || '删除失败')
  }
}

// ── 班级详情（成员 + 已开课）─────────────────────────────────
const detailOpen = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const detailSessions = ref([])

async function openDetail(row) {
  detailOpen.value = true
  detailLoading.value = true
  detail.value = null
  detailSessions.value = []
  pickerOpen.value = false
  try {
    await refreshDetail(row.id)
  } catch (e) {
    toast.error(e.message || '加载班级详情失败')
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail(id) {
  const [d, s] = await Promise.all([
    http.get(`${API}/${id}`),
    http.get(`${API}/${id}/sessions`),
  ])
  detail.value = d
  detailSessions.value = s.list || []
  load()
}

const memberColumns = [
  { key: 'username', title: '用户名' },
  { key: 'nickname', title: '昵称' },
  { key: 'joinedAt', title: '加入时间', width: '150px' },
  { key: 'ops', title: '操作', width: '90px' },
]

async function removeMember(member) {
  const ok = await confirm({
    title: '移出班级',
    message:
      `把「${member.nickname || member.username}」移出「${detail.value.name}」？\n\n` +
      '他之后开的新课不会再自动把他拉进来，但**以前上过的课仍可回顾**。',
    confirmText: '移出',
    danger: true,
  })
  if (!ok) return
  try {
    await http.delete(`${API}/${detail.value.id}/members/${member.userId}`)
    toast.success('已移出')
    await refreshDetail(detail.value.id)
  } catch (e) {
    toast.error(e.message || '移出失败')
  }
}

// ── 加人（搜索 + 多选）────────────────────────────────────
const pickerOpen = ref(false)
const pickerLoading = ref(false)
const pickerKeyword = ref('')
const candidates = ref([])
const picked = ref(new Set())
const adding = ref(false)

async function searchCandidates() {
  pickerLoading.value = true
  try {
    const params = { page: 1, size: 50 }
    if (pickerKeyword.value.trim()) params.keyword = pickerKeyword.value.trim()
    const data = await http.get(`${API}/${detail.value.id}/candidates`, { params })
    candidates.value = data.list || []
  } catch (e) {
    toast.error(e.message || '搜索失败')
  } finally {
    pickerLoading.value = false
  }
}

function openPicker() {
  pickerOpen.value = true
  pickerKeyword.value = ''
  picked.value = new Set()
  searchCandidates()
}

function togglePick(id) {
  const next = new Set(picked.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  picked.value = next
}

async function submitMembers() {
  if (!picked.value.size) return
  adding.value = true
  try {
    const result = await http.post(`${API}/${detail.value.id}/members`, {
      userIds: [...picked.value],
    })
    // 后端会跳过已在班内的人，如实报出实际新增数，别让人以为全加进去了
    toast.success(
      `已加入 ${result.added} 人` + (result.added < picked.value.size ? '（其余已在班内）' : ''),
    )
    pickerOpen.value = false
    await refreshDetail(detail.value.id)
  } catch (e) {
    toast.error(e.message || '加入失败')
  } finally {
    adding.value = false
  }
}

const sessionColumns = [
  { key: 'title', title: '课堂' },
  { key: 'status', title: '状态', width: '100px' },
  { key: 'startedAt', title: '开始时间', width: '150px' },
  { key: 'ops', title: '操作', width: '100px' },
]

const STATUS_TEXT = { NOT_STARTED: '未开始', LIVE: '直播中', PAUSED: '已暂停', ENDED: '已结束' }

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <div>
        <h1 class="page__title">班级管理</h1>
        <p class="page__sub">
          {{
            admin
              ? '全部教师的班级，可跨教师改名、加人、删班（都会记审计）。'
              : '建班、加人。上课时在课件详情页选择「给哪个班上」。'
          }}
        </p>
      </div>
      <FcButton @click="openCreate">＋ 新建班级</FcButton>
    </header>

    <FcCard padding="none">
      <div class="filters">
        <input
          v-model="keyword"
          class="filters__input"
          placeholder="搜索班级名…"
          @keyup.enter="search"
        />
        <!-- 管理端多一个按教师筛选；教师端不需要，因为本来就只看得见自己的 -->
        <input
          v-if="admin"
          v-model="teacherFilter"
          class="filters__input filters__input--narrow"
          placeholder="教师 ID（可留空）"
          @keyup.enter="search"
        />
        <FcButton variant="secondary" size="sm" @click="search">查询</FcButton>
      </div>

      <p v-if="error" class="page__err" role="alert">{{ error }}</p>

      <FcTable :columns="columns" :rows="rows" :loading="loading" empty-text="还没有班级，先建一个">
        <template #cell-description="{ row }">
          <span class="dim">{{ row.description || '—' }}</span>
        </template>

        <template v-if="admin" #cell-teacherName="{ row }">
          <span>{{ row.teacherName || '—' }}</span>
        </template>

        <template #cell-createdAt="{ row }">
          <span class="dim">{{ formatTime(row.createdAt) }}</span>
        </template>

        <template #cell-ops="{ row }">
          <div class="ops">
            <button class="ops__btn" type="button" @click="openDetail(row)">详情 / 加人</button>
            <button class="ops__btn" type="button" @click="openEdit(row)">改名</button>
            <button class="ops__btn ops__btn--danger" type="button" @click="removeGroup(row)">
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
      >下一页</FcButton>
    </div>

    <!-- 新建 / 改名 -->
    <FcModal v-model="formOpen" :title="editingId ? '修改班级' : '新建班级'" width="420px">
      <div class="form">
        <FcInput v-model="form.name" label="班级名" placeholder="如：计科 2301 班" />
        <FcInput v-model="form.description" label="备注" placeholder="可留空" />
        <p v-if="formError" class="form__err" role="alert">{{ formError }}</p>
      </div>
      <template #footer>
        <FcButton variant="secondary" @click="formOpen = false">取消</FcButton>
        <FcButton :loading="saving" @click="submitForm">{{ editingId ? '保存' : '创建' }}</FcButton>
      </template>
    </FcModal>

    <!-- 班级详情 -->
    <FcModal v-model="detailOpen" :title="detail ? `班级：${detail.name}` : '班级详情'" width="720px">
      <FcLoading v-if="detailLoading" />

      <div v-else-if="detail" class="detail">
        <div class="detail__head">
          <span class="detail__count">共 {{ detail.memberCount }} 名学生</span>
          <FcButton size="sm" @click="openPicker">＋ 加人</FcButton>
        </div>

        <FcTable
          :columns="memberColumns"
          :rows="detail.members || []"
          empty-text="这个班还没有学生，点「加人」"
        >
          <template #cell-nickname="{ row }">{{ row.nickname || '—' }}</template>
          <template #cell-joinedAt="{ row }">
            <span class="dim">{{ formatTime(row.joinedAt) }}</span>
          </template>
          <template #cell-ops="{ row }">
            <button class="ops__btn ops__btn--danger" type="button" @click="removeMember(row)">
              移出
            </button>
          </template>
        </FcTable>

        <h3 class="detail__sub">这个班开过的课</h3>
        <FcTable :columns="sessionColumns" :rows="detailSessions" empty-text="还没有给这个班上过课">
          <template #cell-status="{ row }">
            <span class="dim">{{ STATUS_TEXT[row.status] || row.status }}</span>
          </template>
          <template #cell-startedAt="{ row }">
            <span class="dim">{{ formatTime(row.startedAt) || '—' }}</span>
          </template>
          <template #cell-ops="{ row }">
            <RouterLink
              class="ops__link"
              :to="{ name: 'session-record', params: { sessionId: row.id } }"
            >
              回顾
            </RouterLink>
          </template>
        </FcTable>
      </div>

      <template #footer>
        <FcButton variant="secondary" @click="detailOpen = false">关闭</FcButton>
      </template>
    </FcModal>

    <!-- 加人：搜索 + 多选 -->
    <FcModal v-model="pickerOpen" title="添加学生" width="520px">
      <div class="filters">
        <input
          v-model="pickerKeyword"
          class="filters__input"
          placeholder="搜索用户名或昵称…"
          @keyup.enter="searchCandidates"
        />
        <FcButton variant="secondary" size="sm" @click="searchCandidates">搜索</FcButton>
      </div>

      <!-- 已在班内的学生由**后端**排除，这里拿到的就是还能加的人 -->
      <FcLoading v-if="pickerLoading" />
      <FcEmptyState v-else-if="!candidates.length" title="没有可添加的学生（可能都已在班内）" />
      <ul v-else class="picker">
        <li v-for="c in candidates" :key="c.id" class="picker__item">
          <label class="picker__label">
            <input type="checkbox" :checked="picked.has(c.id)" @change="togglePick(c.id)" />
            <span class="picker__name">{{ c.nickname || c.username }}</span>
            <span class="picker__user">{{ c.username }}</span>
          </label>
        </li>
      </ul>

      <template #footer>
        <FcButton variant="secondary" @click="pickerOpen = false">取消</FcButton>
        <FcButton :loading="adding" :disabled="!picked.size" @click="submitMembers">
          加入（已选 {{ picked.size }} 人）
        </FcButton>
      </template>
    </FcModal>
  </div>
</template>

<style scoped>
.page__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: var(--fc-space-5);
}

.page__title {
  font-size: var(--fc-font-xl);
  color: var(--fc-text);
}

.page__sub {
  margin-top: var(--fc-space-1);
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
}

.filters {
  display: flex;
  gap: var(--fc-space-2);
  padding: var(--fc-space-3) var(--fc-space-4);
}

.filters__input {
  flex: 1;
  height: 34px;
  padding: 0 var(--fc-space-3);
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font);
}

.filters__input--narrow {
  flex: 0 0 160px;
}

.filters__input:focus {
  outline: none;
  border-color: var(--fc-primary);
}

.page__err {
  padding: 0 var(--fc-space-4) var(--fc-space-3);
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
}

.dim {
  color: var(--fc-text-faint);
  font-size: var(--fc-font-xs);
}

.ops {
  display: flex;
  gap: var(--fc-space-3);
}

.ops__btn {
  background: none;
  border: none;
  padding: 0;
  font-size: var(--fc-font-xs);
  color: var(--fc-text-muted);
  cursor: pointer;
}

.ops__btn:hover {
  color: var(--fc-primary);
}

.ops__btn--danger {
  color: var(--fc-danger);
}

.ops__link {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-muted);
}

.ops__link:hover {
  color: var(--fc-primary);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--fc-space-3);
  margin-top: var(--fc-space-4);
}

.pager__info {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
}

.form {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-3);
}

.form__err {
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
}

.detail {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-4);
}

.detail__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.detail__count {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-muted);
}

.detail__sub {
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text);
  padding-top: var(--fc-space-2);
  border-top: 1px solid var(--fc-border);
}

.picker {
  list-style: none;
  max-height: 320px;
  overflow-y: auto;
}

.picker__item {
  border-bottom: 1px solid var(--fc-border);
}

.picker__label {
  display: flex;
  align-items: center;
  gap: var(--fc-space-3);
  padding: var(--fc-space-2) var(--fc-space-1);
  cursor: pointer;
}

.picker__name {
  font-size: var(--fc-font-sm);
}

.picker__user {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}
</style>
