<script setup>
// 开课弹窗。从 CoursewareDetailPage 抽出来，供**课件详情页**与**教师首页**共用，
// 避免同一套「选人建课」逻辑写两份、日后改一处漏一处。
//
// 两种用法：
//   1. 课件已定（课件详情页）：只传 `coursewareId` → 只有「给谁上课」一步；
//   2. 课件未定（教师首页）  ：传 `coursewares` 列表 → 两步，先选课件再选人。
//
// 为什么必须**显式**选授课对象：缺省若是「公开」，某次忘了选就是把课对所有人开放；
// 缺省若是「限定」，忘了选就开出一节谁也看不到的课。两种静默失败都不该被允许，
// 所以没选之前提交按钮禁用。真正的校验在后端（ClassSessionService.create）。
//
// 为什么点「开始上课」后由**本组件**负责跳转：开课之后唯一合理的去处就是直播控制台，
// 让两个调用方各写一遍 router.push 只会多一处可能漏掉的地方。
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import http from '@/api/http'
import { FcButton, FcEmptyState, FcLoading, FcModal } from '@/components/base'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 已经定好上哪份课件。给了就跳过第一步。 */
  coursewareId: { type: [Number, String], default: null },
  /** 候选课件（教师首页的「我的课件」）。没给 coursewareId 时用它做第一步。 */
  coursewares: { type: Array, default: () => [] },
})

const emit = defineEmits(['update:modelValue', 'started'])

const router = useRouter()

/** FcModal 要的是 v-model，这里把 prop 透传成可写计算属性。 */
const open = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

/** 需不需要先选课件。课件详情页已经把课件定死了，就只剩一步。 */
const needPickCourseware = computed(() => !props.coursewareId)

const pickedCoursewareId = ref(null)
const myGroups = ref([])
const myStudents = ref([])
const audience = reactive({ groupIds: [], studentIds: [], isPublic: false })
const loading = ref(false)
const starting = ref(false)
const actionError = ref('')

/** 最终要上课的那份课件：调用方给定的优先，否则用第一步选中的。 */
const effectiveCoursewareId = computed(() => props.coursewareId || pickedCoursewareId.value)

/** 三选一的禁用条件：公开课，或至少选了一个班/一个学生。 */
const audienceChosen = computed(
  () => audience.isPublic || audience.groupIds.length > 0 || audience.studentIds.length > 0,
)

/** 两步都齐了才能提交。 */
const canSubmit = computed(() => !!effectiveCoursewareId.value && audienceChosen.value)

// 每次打开都重置并重新拉一次班级名单：
// 老师可能刚在别的标签页建了班，缓存住会让他在下拉里找不到新班级。
watch(open, async (isOpen) => {
  if (!isOpen) return
  pickedCoursewareId.value = null
  audience.groupIds = []
  audience.studentIds = []
  audience.isPublic = false
  actionError.value = ''
  loading.value = true
  try {
    // 两个都**可能为空**（新教师还没建过班），不该当成错误
    const [groups, students] = await Promise.all([
      http.get('/class-groups/mine'),
      http.get('/class-groups/my-students'),
    ])
    myGroups.value = groups.list || []
    myStudents.value = students.list || []
  } catch (e) {
    actionError.value = e.message || '加载班级失败'
  } finally {
    loading.value = false
  }
})

/** 选了公开课就把班级/学生清掉（后端也互斥，这里让界面不会出现自相矛盾的状态）。 */
function choosePublic() {
  audience.isPublic = true
  audience.groupIds = []
  audience.studentIds = []
}

function chooseRestricted() {
  audience.isPublic = false
}

function toggleGroup(id) {
  const next = new Set(audience.groupIds)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  audience.groupIds = [...next]
  if (audience.groupIds.length) audience.isPublic = false
}

function toggleStudent(id) {
  const next = new Set(audience.studentIds)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  audience.studentIds = [...next]
  if (audience.studentIds.length) audience.isPublic = false
}

async function submit() {
  if (!canSubmit.value) return
  starting.value = true
  actionError.value = ''
  try {
    const body = { coursewareId: effectiveCoursewareId.value }
    if (audience.isPublic) {
      body.visibility = 'PUBLIC'
    } else {
      if (audience.groupIds.length) body.classGroupIds = audience.groupIds
      if (audience.studentIds.length) body.studentIds = audience.studentIds
    }
    const data = await http.post('/session', body)
    open.value = false
    emit('started', data)
    router.push(`/teach/${data.id}`)
  } catch (e) {
    actionError.value = e.message || '开课失败'
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <FcModal v-model="open" title="开始上课" width="560px">
    <FcLoading v-if="loading" />

    <div v-else class="audience">
      <!-- ① 选课件。只在调用方没给定课件时出现（教师首页那条路）。 -->
      <section v-if="needPickCourseware" class="audience__block">
        <h4 class="audience__title">
          ① 上哪份课件
          <span class="audience__note">只列出你自己上传的</span>
        </h4>
        <FcEmptyState
          v-if="!coursewares.length"
          title="你还没有上传过课件"
          description="先去「上传课件」传一份，再回来开课。"
        />
        <div v-else class="cw-pick">
          <button
            v-for="c in coursewares"
            :key="c.id"
            type="button"
            class="cw-pick__item"
            :class="{ 'cw-pick__item--on': pickedCoursewareId === c.id }"
            @click="pickedCoursewareId = c.id"
          >
            <span class="cw-pick__name" :title="c.name">{{ c.name }}</span>
            <span class="cw-pick__pages">{{ c.pageCount ?? 0 }} 页</span>
          </button>
        </div>
      </section>

      <!-- ② 选班级（可多选）-->
      <section class="audience__block">
        <h4 class="audience__title">
          <template v-if="needPickCourseware">② 给谁上课 · </template>选择班级
          <span class="audience__note">选两个以上就是「合班上课」，两班共有的人只算一次</span>
        </h4>
        <FcEmptyState
          v-if="!myGroups.length"
          title="你还没有班级"
          description="可以先去「班级管理」建一个，或直接勾选下面的同学。"
        />
        <div v-else class="audience__chips">
          <button
            v-for="g in myGroups"
            :key="g.id"
            type="button"
            class="chip"
            :class="{ 'chip--on': audience.groupIds.includes(g.id) }"
            @click="toggleGroup(g.id)"
          >
            {{ g.name }}（{{ g.memberCount }} 人）
          </button>
        </div>
      </section>

      <!-- ③ 单独勾选同学 -->
      <section class="audience__block">
        <h4 class="audience__title">单独勾选同学</h4>
        <p class="audience__note">
          这里只列出你名下班级里的学生 —— 不会把全校学生名单拉出来。
        </p>
        <FcEmptyState v-if="!myStudents.length" title="还没有可勾选的学生" />
        <div v-else class="audience__chips">
          <button
            v-for="s in myStudents"
            :key="s.id"
            type="button"
            class="chip"
            :class="{ 'chip--on': audience.studentIds.includes(s.id) }"
            @click="toggleStudent(s.id)"
          >
            {{ s.nickname || s.username }}
          </button>
        </div>
      </section>

      <!-- ④ 公开课 -->
      <section class="audience__block">
        <label class="audience__radio">
          <input
            type="radio"
            name="visibility"
            :checked="audience.isPublic"
            @change="choosePublic"
          />
          <span>设为公开课（所有学生都能看到，与上面的选择互斥）</span>
        </label>
        <label class="audience__radio">
          <input
            type="radio"
            name="visibility"
            :checked="!audience.isPublic"
            @change="chooseRestricted"
          />
          <span>只给上面选中的班级 / 同学看</span>
        </label>
      </section>

      <p v-if="actionError" class="audience__err" role="alert">{{ actionError }}</p>
    </div>

    <template #footer>
      <FcButton variant="secondary" @click="open = false">取消</FcButton>
      <FcButton :loading="starting" :disabled="!canSubmit" @click="submit">
        开始上课
      </FcButton>
    </template>
  </FcModal>
</template>

<style scoped>
.audience {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-4);
}

.audience__block {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-2);
}

.audience__title {
  font-size: var(--fc-font-sm);
  font-weight: var(--fc-weight-semibold);
  color: var(--fc-text);
}

.audience__note {
  margin-left: var(--fc-space-2);
  font-size: var(--fc-font-xs);
  font-weight: normal;
  color: var(--fc-text-faint);
}

/* 用可点的小胶囊而不是下拉多选：班级数量通常个位数，
   一眼看全 + 直接点比「展开下拉 → 勾选 → 收起」快得多 */
.audience__chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--fc-space-2);
}

.chip {
  padding: 4px 10px;
  border: 1px solid var(--fc-border-strong);
  border-radius: 999px;
  background: var(--fc-bg-panel);
  font-size: var(--fc-font-xs);
  color: var(--fc-text);
  cursor: pointer;
  transition: border-color var(--fc-transition), background var(--fc-transition);
}

.chip:hover {
  border-color: var(--fc-primary);
}

.chip--on {
  border-color: var(--fc-primary);
  background: var(--fc-primary-tint);
  color: var(--fc-primary);
  font-weight: var(--fc-weight-medium);
}

/* 课件名可能很长，所以这里不用胶囊而用**整行的列表**：
   胶囊会被长名字撑成一整行高矮不齐，列表则能整齐地一行一个、超出省略。 */
.cw-pick {
  display: flex;
  flex-direction: column;
  gap: var(--fc-space-1);
  max-height: 208px;
  overflow-y: auto;
}

.cw-pick__item {
  display: flex;
  align-items: center;
  gap: var(--fc-space-3);
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--fc-border-strong);
  border-radius: var(--fc-radius-sm);
  background: var(--fc-bg-panel);
  font-size: var(--fc-font-sm);
  color: var(--fc-text);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--fc-transition), background var(--fc-transition);
}

.cw-pick__item:hover {
  border-color: var(--fc-primary);
}

.cw-pick__item--on {
  border-color: var(--fc-primary);
  background: var(--fc-primary-tint);
  font-weight: var(--fc-weight-medium);
}

.cw-pick__name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cw-pick__pages {
  flex-shrink: 0;
  font-size: var(--fc-font-xs);
  font-weight: normal;
  color: var(--fc-text-faint);
}

.audience__radio {
  display: flex;
  align-items: center;
  gap: var(--fc-space-2);
  font-size: var(--fc-font-sm);
  cursor: pointer;
}

.audience__err {
  color: var(--fc-danger);
  font-size: var(--fc-font-sm);
}
</style>
