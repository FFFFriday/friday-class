<script setup>
// 「正在直播」区块。教师首页与学生首页共用。
//
// 为什么单独拆成组件：两种角色的首页都要这个入口，而它自己是一次独立请求
// （GET /api/session/active）。塞进任一首页都会让另一个重复写一遍。
//
// 没有直播时**整块不渲染**（v-if），不占位、不显示空状态——
// 首页顶部挂一个「暂无直播」的框只会占地方。
import { onMounted, ref } from 'vue'
import http from '@/api/http'

const sessions = ref([])

async function load() {
  try {
    const data = await http.get('/session/active')
    sessions.value = data.list || []
  } catch {
    // 直播列表拿不到不该影响首页主内容，静默为空即可
    sessions.value = []
  }
}

onMounted(load)
</script>

<template>
  <section v-if="sessions.length" class="live">
    <header class="live-head">
      <h2 class="live-title">
        <span class="dot" aria-hidden="true"></span>
        正在直播
      </h2>
      <span class="live-count">{{ sessions.length }} 节课进行中</span>
    </header>

    <ul class="live-list">
      <li v-for="s in sessions" :key="s.id">
        <router-link class="live-item" :to="`/live/${s.id}`">
          <span class="live-name">{{ s.title }}</span>
          <span class="live-meta">{{ s.teacherName }} · 第 {{ s.currentPage ?? 1 }} 页</span>
          <span class="live-go">进入课堂 →</span>
        </router-link>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.live {
  border: 1px solid #f3ddd4;
  border-radius: 12px;
  padding: 20px 22px;
  margin-bottom: 30px;
  background: linear-gradient(180deg, #fff8f5 0%, #fffdfc 100%);
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

/* 呼吸灯：让「正在直播」一眼可辨，而不是靠读文字 */
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
