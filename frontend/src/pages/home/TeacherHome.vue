<script setup>
// 教师首页：慕课分节型（欢迎横幅 + 多节横向卡片流）。
//
// 数据来源只有一个真实接口 GET /api/courseware，没有别的。
// 「我的课件」是前端按 uploaderId 从列表里筛出来的——后端 list 接口目前
// 只支持 keyword / status 两个筛选条件，没有 uploaderId 参数。
// ⚠️ 已知局限：一次只取前 FETCH_SIZE 条来筛，课件总数超过这个数时
//    「我的课件」会漏。正解是后端给 list 加一个 mine/uploaderId 参数（另开任务）。
import { computed, onMounted, ref } from 'vue'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import CoursewareCard from '@/components/CoursewareCard.vue'
import LiveSessions from '@/components/LiveSessions.vue'

const auth = useAuthStore()

const FETCH_SIZE = 60 // 见上方局限说明
const SECTION_SIZE = 4 // 每节展示一行

const all = ref([])
const loading = ref(false)
const error = ref('')

const mine = computed(() =>
  all.value.filter((c) => c.uploaderId != null && c.uploaderId === auth.user?.id),
)
const mySection = computed(() => mine.value.slice(0, SECTION_SIZE))
const allSection = computed(() => all.value.slice(0, SECTION_SIZE))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await http.get('/courseware', { params: { page: 1, size: FETCH_SIZE } })
    all.value = data.list || []
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <section class="banner">
      <div class="banner-inner">
        <h1 class="banner-title">你好，{{ auth.displayName }}</h1>
        <p class="banner-sub">今天想上哪一节课？</p>
        <div class="banner-actions">
          <router-link class="btn solid" to="/upload">上传课件</router-link>
          <router-link class="btn ghost" to="/courseware">浏览课程中心</router-link>
        </div>
      </div>
    </section>

    <!-- 正在直播：有课在进行时排在最上面，比课件列表更紧急。没有课时整块不渲染 -->
    <LiveSessions />

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="error" class="hint error-text">{{ error }}</div>

    <template v-else>
      <section class="section">
        <header class="section-head">
          <h2 class="section-title">我的课件</h2>
          <router-link class="more" to="/courseware">查看全部 →</router-link>
        </header>
        <div v-if="mySection.length" class="row">
          <CoursewareCard v-for="c in mySection" :key="c.id" :courseware="c" />
        </div>
        <p v-else class="empty">
          你还没有上传过课件。<router-link class="link" to="/upload">现在上传第一个 →</router-link>
        </p>
      </section>

      <section class="section">
        <header class="section-head">
          <h2 class="section-title">全部课件</h2>
          <router-link class="more" to="/courseware">查看全部 →</router-link>
        </header>
        <div v-if="allSection.length" class="row">
          <CoursewareCard v-for="c in allSection" :key="c.id" :courseware="c" />
        </div>
        <p v-else class="empty">平台上还没有任何课件。</p>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page {
  max-width: 1120px;
  margin: 0 auto;
}

.banner {
  border-radius: 14px;
  padding: 40px 44px;
  margin-bottom: 34px;
  background: linear-gradient(120deg, #d97757 0%, #e08d6a 55%, #eeae91 100%);
  color: #fff;
}

.banner-title {
  font-size: 28px;
  font-weight: 700;
}

.banner-sub {
  margin-top: 10px;
  font-size: 15px;
  opacity: 0.92;
}

.banner-actions {
  margin-top: 22px;
  display: flex;
  gap: 12px;
}

.btn {
  display: inline-block;
  padding: 10px 22px;
  border-radius: 8px;
  font-size: 14px;
  text-decoration: none;
  transition: background 0.15s, color 0.15s;
}

/* 白底橙字，压在橙色横幅上对比度足够 */
.btn.solid {
  background: #fff;
  color: #c9694a;
  font-weight: 600;
}

.btn.solid:hover {
  background: #fdf1ec;
}

/* 描边按钮：半透明白边，比实心按钮弱一级 */
.btn.ghost {
  border: 1px solid rgba(255, 255, 255, 0.75);
  color: #fff;
}

.btn.ghost:hover {
  background: rgba(255, 255, 255, 0.15);
}

.section {
  margin-bottom: 34px;
}

.section-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-title {
  font-size: 18px;
  color: #333;
}

.more {
  font-size: 13px;
  color: #999;
  text-decoration: none;
}

.more:hover {
  color: #d97757;
}

.row {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}

.empty {
  padding: 22px 0;
  font-size: 14px;
  color: #999;
}

.link {
  color: #d97757;
  text-decoration: none;
}

.hint {
  text-align: center;
  color: #999;
  padding: 60px 0;
  font-size: 14px;
}

.error-text {
  color: #e74c3c;
}

/* 窄屏收成两列，避免四列卡片被挤成竖条 */
@media (max-width: 900px) {
  .row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .banner {
    padding: 28px 24px;
  }
}
</style>
