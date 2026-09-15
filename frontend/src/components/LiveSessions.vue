<script setup>
// 「正在上课」区块。教师首页与学生首页共用，但**内容按角色不同**。
//
// 分成两块的理由：
//   · 教师 —— 要看的是「我的课在哪」。所以第一块是我自己的课（含还没开始翻页的），
//     入口是控制台 /teach/{id}；第二块才是别人的直播，进去只能旁观。
//     以前这里只有一份「正在直播」列表，老师在上面找不到自己的课，
//     点进去还变成学生视角 —— 这是 F003 反馈 #2 与 #3 的直接原因。
//   · 学生 —— 只有一份「正在直播」，进 /live/{id}。
//
// 两个数据源：
//   · GET /api/session/active —— 所有 status=LIVE 的课（谁都能看）
//   · GET /api/session/mine   —— 我自己的、未结束的课（含 NOT_STARTED，仅教师）
// 后者的存在是必须的：刚开完课还没翻页时状态是 NOT_STARTED，不在 active 里。
//
// 两块都为空时**整块不渲染**（v-if），不占位、不显示空状态——
// 首页顶部挂一个「暂无直播」的框只会占地方。
import { computed, onMounted, ref } from 'vue'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/** 我自己的课（仅教师，含未开始的）。 */
const mine = ref([])
/** 别人的直播（教师视角）/ 全部直播（学生视角）。 */
const live = ref([])

const hasAnything = computed(() => mine.value.length > 0 || live.value.length > 0)

async function load() {
  try {
    const data = await http.get('/session/active')
    const all = data.list || []
    live.value = auth.isTeacher
      ? all.filter((s) => s.teacherId !== auth.user?.id)
      : all
  } catch {
    // 直播列表拿不到不该影响首页主内容，静默为空即可
    live.value = []
  }

  if (!auth.isTeacher) {
    mine.value = []
    return
  }

  try {
    const data = await http.get('/session/mine')
    mine.value = data.list || []
  } catch {
    // 拿不到就只显示别人的课。不把它当成首页错误——浏览课件才是主内容。
    mine.value = []
  }
}

/** 课堂状态 → 给学生看的中文。 */
function statusText(s) {
  return s.status === 'NOT_STARTED' ? '未开始' : `第 ${s.currentPage ?? 1} 页`
}

onMounted(load)
</script>

<template>
  <template v-if="hasAnything">
    <!-- 我的课堂：只在教师首页出现，永远排在最前 -->
    <section v-if="mine.length" class="live live-mine">
      <header class="live-head">
        <h2 class="live-title">
          <span class="dot" aria-hidden="true"></span>
          我的课堂
        </h2>
        <span class="live-count">{{ mine.length }} 节进行中</span>
      </header>

      <ul class="live-list">
        <li v-for="s in mine" :key="s.id">
          <router-link class="live-item" :to="`/teach/${s.id}`">
            <span class="live-name">{{ s.title }}</span>
            <span class="live-meta">{{ s.coursewareName }} · {{ statusText(s) }}</span>
            <span class="live-go">回到控制台 →</span>
          </router-link>
        </li>
      </ul>
    </section>

    <section v-if="live.length" class="live">
      <header class="live-head">
        <h2 class="live-title">
          <span class="dot" aria-hidden="true"></span>
          {{ auth.isTeacher ? '其他老师的课堂' : '正在直播' }}
        </h2>
        <span class="live-count">{{ live.length }} 节课进行中</span>
      </header>

      <ul class="live-list">
        <li v-for="s in live" :key="s.id">
          <router-link class="live-item" :to="`/live/${s.id}`">
            <span class="live-name">{{ s.title }}</span>
            <span class="live-meta">{{ s.teacherName }} · {{ statusText(s) }}</span>
            <span class="live-go">进入课堂 →</span>
          </router-link>
        </li>
      </ul>
    </section>
  </template>
</template>

<style scoped>
.live {
  border: 1px solid #f3ddd4;
  border-radius: 12px;
  padding: 20px 22px;
  margin-bottom: 30px;
  background: linear-gradient(180deg, #fff8f5 0%, #fffdfc 100%);
}

/* 自己的课给一层更实的底色，一眼能和「别人的课」区分开 */
.live-mine {
  background: linear-gradient(180deg, #fff1ea 0%, #fff8f5 100%);
  border-color: #eccbbd;
}

.live-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}

.live-title {
  font-size: 17px;
  color: #c9694a;
  display: flex;
  align-items: center;
  gap: 8px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #e74c3c;
  animation: blink 1.4s infinite;
}

/* 呼吸灯：让「正在上课」一眼可辨，而不是靠读文字 */
@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}

.live-count {
  font-size: 13px;
  color: #b08b7c;
}

.live-list {
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.live-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #f0e0d9;
  border-radius: 8px;
  text-decoration: none;
  color: inherit;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.live-item:hover {
  border-color: #d97757;
  box-shadow: 0 3px 12px rgba(217, 119, 87, 0.12);
}

.live-name {
  flex: 1;
  font-size: 14px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.live-meta {
  font-size: 13px;
  color: #999;
  white-space: nowrap;
}

.live-go {
  font-size: 13px;
  color: #d97757;
  white-space: nowrap;
}
</style>
