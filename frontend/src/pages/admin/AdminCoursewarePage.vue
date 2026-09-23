<script setup>
// 管理端：课件与存储。
import { computed, onMounted, ref } from 'vue'
import http from '@/api/http'
import { useToast } from '@/composables/useToast'
import { confirm } from '@/composables/useConfirm'
import { useBulkDelete } from '@/composables/useBulkDelete'
import { FcButton, FcCard, FcLoading, FcTable } from '@/components/base'

const toast = useToast()

const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 20
const loading = ref(false)
const error = ref('')
const busy = ref(false)

const storage = ref(null)
const orphans = ref([])
const orphansLoaded = ref(false)
const orphansLoading = ref(false)
/** 被勾选要清理的孤立项。默认全不选——「先列后删」的重点就是**默认不删**。 */
const picked = ref(new Set())

const columns = [
  // 勾选列没有标题（表头放的是「全选」复选框，见 #header-select）
  { key: 'select', title: '', width: '44px' },
  { key: 'name', title: '课件' },
  { key: 'pageCount', title: '页数', width: '70px' },
  { key: 'uploaderName', title: '上传者', width: '110px' },
  { key: 'size', title: '占用', width: '140px' },
  { key: 'ops', title: '操作', width: '180px' },
]

const pickedCount = computed(() => picked.value.size)

function formatBytes(bytes) {
  const value = Number(bytes || 0)
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

async function load() {
  loading.value = true
  error.value = ''
  // 换页后行整批换掉，旧的勾选必须清空 ——
  // 否则「已选 3 项」里混着上一页的行，批量删除会删掉用户根本没看见的课件。
  clearRowPicks()
  try {
    const [data, st] = await Promise.all([
      http.get('/admin/coursewares', { params: { page: page.value, size } }),
      http.get('/admin/storage/overview'),
    ])
    rows.value = data.list || []
    total.value = data.total || 0
    storage.value = st
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

function goPage(delta) {
  const next = page.value + delta
  if (next < 1 || next > Math.ceil(total.value / size)) return
  page.value = next
  load()
}

/** 真正的那一次删除请求。单条与批量**共用同一个函数体**，两条路走同一个后端接口。 */
function deleteCourseware(row) {
  return http.delete(`/admin/coursewares/${row.id}`)
}

async function removeCourseware(row) {
  const ok = await confirm({
    title: '删除课件',
    message:
      `删除课件《${row.name}》？\n\n` +
      '会同时清理三处：数据库记录、源 .pptx 文件、以及该课件的幻灯片目录。\n' +
      '此操作不可撤销（数据库是软删除，但文件是真的删掉了）。',
    confirmText: '删除',
    danger: true,
  })
  if (!ok) return
  busy.value = true
  try {
    const report = await deleteCourseware(row)
    toast.success(
      `已删除。源文件${report.sourceRemoved ? '已清理' : '未找到'}，` +
        `幻灯片清理 ${report.slidesRemoved} 个文件`,
    )
    load()
  } catch (e) {
    toast.error(e.message || '删除失败')
  } finally {
    busy.value = false
  }
}

// ⚠ 这里**必须给解构出来的名字起别名**：本页上面已经有一组 picked / pickedCount /
// togglePick，是「孤立文件清理」在用的（选的是路径，不是课件 id）。
// 不换名字就会把那组覆盖掉，孤立清理会静默失效。
const {
  picked: rowPicked,
  pickedCount: rowPickedCount,
  allPicked: allRowsPicked,
  removing: removingRows,
  togglePick: toggleRowPick,
  toggleAll: toggleAllRows,
  clearPicked: clearRowPicks,
  removePicked: removePickedRows,
} = useBulkDelete({
  rows,
  idOf: (row) => row.id,
  nameOf: (row) => row.name,
  removeOne: deleteCourseware,
  noun: '个课件',
  note: '会同时清理三处：数据库记录、源 .pptx 文件、以及该课件的幻灯片目录。此操作不可撤销。',
  onDone: load,
})

async function reparse(row) {
  const ok = await confirm({
    title: '重新解析课件',
    message:
      `重新解析《${row.name}》？\n\n` +
      '这会「真的调用付费大模型」（百页课件约 0.8 元），并且覆盖已有的解析结果。\n' +
      '解析过程中，学生的 AI 助手会暂时收到「课件还在解析中」。',
    confirmText: '重新解析',
    danger: true,
  })
  if (!ok) return
  busy.value = true
  try {
    await http.post(`/admin/coursewares/${row.id}/reparse`)
    toast.success('已提交解析，可在课件详情页看进度')
  } catch (e) {
    toast.error(e.message || '提交失败')
  } finally {
    busy.value = false
  }
}

async function loadOrphans() {
  orphansLoading.value = true
  try {
    orphans.value = await http.get('/admin/storage/orphans')
    orphansLoaded.value = true
    picked.value = new Set()
  } catch (e) {
    toast.error(e.message || '扫描失败')
  } finally {
    orphansLoading.value = false
  }
}

function togglePick(path) {
  const next = new Set(picked.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  picked.value = next
}

/**
 * 清理选中的孤立文件。
 *
 * 这里**没有任何「一键全选并删除」的入口**——孤立文件清理是不可逆的，
 * 误判一次就是把整个存储目录扫空。要求人工逐条勾选是有意的摩擦。
 */
async function cleanPicked() {
  if (!picked.value.size) return
  const ok = await confirm({
    title: '清理孤立文件',
    message: `清理选中的 ${picked.value.size} 个孤立项？\n\n删除后无法恢复。服务端会再校验一次，已经不是孤立的会被自动跳过。`,
    confirmText: '清理',
    danger: true,
  })
  if (!ok) return
  busy.value = true
  try {
    const report = await http.post('/admin/storage/orphans/clean', {
      paths: [...picked.value],
    })
    toast.success(
      `实删 ${report.removed} 个，跳过 ${report.skipped.length} 个` +
        (report.skipped.length ? '（已不是孤立文件）' : ''),
    )
    await loadOrphans()
    load()
  } catch (e) {
    toast.error(e.message || '清理失败')
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="page__head">
      <h1 class="page__title">课件与存储</h1>
      <div class="page__ops">
        <!-- 没勾选时不显示：一个永远是灰的按钮只是噪音。
             课件删除会真的删掉磁盘上的文件，确认框里写明这一点。 -->
        <FcButton
          v-if="rowPickedCount"
          variant="danger"
          size="sm"
          :loading="removingRows"
          @click="removePickedRows"
        >
          删除选中（{{ rowPickedCount }}）
        </FcButton>
        <FcButton variant="secondary" size="sm" @click="load">刷新</FcButton>
      </div>
    </header>

    <div v-if="storage" class="stats">
      <div class="stat">
        <span class="stat__value">{{ storage.coursewareCount }}</span>
        <span class="stat__label">课件</span>
      </div>
      <div class="stat">
        <span class="stat__value">{{ storage.slidesDirCount }}</span>
        <span class="stat__label">幻灯片目录</span>
      </div>
      <div class="stat">
        <span class="stat__value">{{ formatBytes(storage.coursewareBytes) }}</span>
        <span class="stat__label">源文件占用</span>
      </div>
      <div class="stat">
        <span class="stat__value">{{ formatBytes(storage.slidesBytes) }}</span>
        <span class="stat__label">幻灯片占用</span>
      </div>
      <div class="stat">
        <span class="stat__value">{{ formatBytes(storage.totalBytes) }}</span>
        <span class="stat__label">合计</span>
      </div>
    </div>

    <p v-if="error" class="page__err" role="alert">{{ error }}</p>

    <FcCard padding="none">
      <FcTable :columns="columns" :rows="rows" :loading="loading" empty-text="还没有课件">
        <template #header-select>
          <input
            type="checkbox"
            class="row-pick"
            :checked="allRowsPicked"
            :aria-label="allRowsPicked ? '取消全选本页' : '全选本页'"
            @change="toggleAllRows"
          />
        </template>

        <template #cell-select="{ row }">
          <input
            type="checkbox"
            class="row-pick"
            :checked="rowPicked.has(row.id)"
            :aria-label="`选择课件 ${row.name}`"
            @change="toggleRowPick(row.id)"
          />
        </template>

        <!--
          课件名做成链接：管理员在这个列表里看到课件，十有八九是想点进去看解析进度、
          知识点或提问，而不是只想读一遍名字。详情页 /courseware/:id 只要求登录、
          没有角色限制，管理员进得去。
        -->
        <template #cell-name="{ row }">
          <RouterLink class="name" :to="{ name: 'courseware-detail', params: { id: row.id } }">
            {{ row.name }}
          </RouterLink>
        </template>

        <template #cell-pageCount="{ row }">
          <span class="dim">{{ row.pageCount ?? '—' }}</span>
        </template>

        <template #cell-uploaderName="{ row }">
          <span class="dim">{{ row.uploaderName || '—' }}</span>
        </template>

        <template #cell-size="{ row }">
          <span class="dim">
            源 {{ formatBytes(row.fileSizeBytes) }} / 页 {{ formatBytes(row.slidesSizeBytes) }}
          </span>
        </template>

        <template #cell-ops="{ row }">
          <div class="ops">
            <button class="ops__btn" type="button" :disabled="busy" @click="reparse(row)">
              重新解析
            </button>
            <button
              class="ops__btn ops__btn--danger"
              type="button"
              :disabled="busy"
              @click="removeCourseware(row)"
            >
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
      <span class="pager__info">第 {{ page }} 页 · 共 {{ total }} 份</span>
      <FcButton
        variant="secondary"
        size="sm"
        :disabled="page >= Math.ceil(total / size)"
        @click="goPage(1)"
      >
        下一页
      </FcButton>
    </div>

    <!-- 孤立文件 -->
    <FcCard
      title="孤立文件"
      subtitle="磁盘上有、但没有任何课件引用的文件。先扫描、再人工确认，最后才清理"
    >
      <template #extra>
        <FcButton variant="secondary" size="sm" :loading="orphansLoading" @click="loadOrphans">
          {{ orphansLoaded ? '重新扫描' : '开始扫描' }}
        </FcButton>
      </template>

      <FcLoading v-if="orphansLoading" text="扫描中…" />

      <template v-else-if="!orphansLoaded">
        <p class="hint">
          还没有扫描。孤立文件可能是「上次删除课件时文件没删干净」留下的，
          也可能是别的原因。<strong>点扫描只列出来，不会删任何东西。</strong>
        </p>
      </template>

      <template v-else-if="!orphans.length">
        <p class="hint">没有发现孤立文件，存储很干净。</p>
      </template>

      <template v-else>
        <ul class="orphans">
          <li v-for="o in orphans" :key="o.path" class="orphans__item">
            <label class="orphans__pick">
              <input
                type="checkbox"
                :checked="picked.has(o.path)"
                @change="togglePick(o.path)"
              />
              <span class="orphans__path">{{ o.path }}</span>
            </label>
            <span class="dim">{{ o.kind === 'DIR' ? '目录' : '文件' }} · {{ formatBytes(o.sizeBytes) }}</span>
            <span class="orphans__reason">{{ o.reason }}</span>
          </li>
        </ul>

        <div class="orphans__foot">
          <span class="dim">已选 {{ pickedCount }} 项</span>
          <FcButton
            variant="danger"
            size="sm"
            :disabled="!pickedCount || busy"
            @click="cleanPicked"
          >
            清理选中项
          </FcButton>
        </div>
      </template>
    </FcCard>
  </div>
</template>

<style scoped>
.page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--fc-space-5);
}

/* 标题右侧的操作区：批量删除按钮出现时，它和「刷新」要并排且留间距 */
.page__ops {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
}

/* 表格里行的勾选框。浏览器默认只有 13px 左右偏小，给到 16px。
   名字用 .row-pick 而不是 .pick：ClassGroupManager 里 .pick 已被别的东西占用，
   三页统一用同一个名字，改的时候不容易漏。 */
.row-pick {
  width: 16px;
  height: 16px;
  accent-color: var(--fc-primary);
  cursor: pointer;
  vertical-align: middle;
}

.page__title {
  font-size: var(--fc-font-xl);
  color: var(--fc-text);
}

.stats {
  display: flex;
  gap: var(--fc-space-3);
  flex-wrap: wrap;
  margin-bottom: var(--fc-space-4);
}

.stat {
  flex: 1;
  min-width: 120px;
  padding: var(--fc-space-3) var(--fc-space-4);
  background: var(--fc-bg-panel);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stat__value {
  font-size: var(--fc-font-lg);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-primary);
  font-variant-numeric: tabular-nums;
}

.stat__label {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

/* 现在是链接，但外观仍是普通文字：只在悬停时给下划线，
   避免整列变成一片蓝色链接、看起来像导航而不是内容 */
.name {
  color: var(--fc-text);
  text-decoration: none;
}
.name:hover {
  color: var(--fc-primary);
  text-decoration: underline;
}

.dim {
  color: var(--fc-text-faint);
  font-size: var(--fc-font-xs);
}

.ops {
  display: flex;
  gap: var(--fc-space-2);
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
.ops__btn--danger {
  color: var(--fc-danger);
}
.ops__btn--danger:hover:not(:disabled) {
  background: var(--fc-danger-bg);
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--fc-space-4);
  margin: var(--fc-space-4) 0 var(--fc-space-5);
}

.pager__info {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-faint);
}

.hint {
  font-size: var(--fc-font-sm);
  color: var(--fc-text-faint);
  line-height: 1.8;
  margin: 0;
}

.orphans {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
}

.orphans__item {
  display: flex;
  align-items: center;
  gap: var(--fc-space-3);
  flex-wrap: wrap;
  padding: var(--fc-space-2) var(--fc-space-3);
  border: 1px solid var(--fc-border);
  border-radius: var(--fc-radius);
  font-size: var(--fc-font-sm);
}

.orphans__pick {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  flex: 1;
  min-width: 200px;
  cursor: pointer;
}

.orphans__path {
  font-family: var(--fc-font-mono);
  font-size: var(--fc-font-xs);
  overflow-wrap: anywhere;
}

.orphans__reason {
  font-size: var(--fc-font-xs);
  color: var(--fc-text-faint);
}

.orphans__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--fc-space-3);
}

.page__err {
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
}
</style>
