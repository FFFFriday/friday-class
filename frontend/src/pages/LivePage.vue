<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'

const route = useRoute()
const session = ref(null)
const question = ref('')
const records = ref([])
const asking = ref(false)
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    session.value = await http.get(`/session/${route.params.sessionId}`)
    const data = await http.get('/qa/records', { params: { sessionId: route.params.sessionId } })
    records.value = data.list
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function ask() {
  if (!question.value.trim()) return
  asking.value = true
  try {
    const data = await http.post('/qa/ask', {
      sessionId: Number(route.params.sessionId),
      pageId: 1,
      question: question.value.trim(),
    })
    records.value.push(data)
    question.value = ''
  } catch (e) {
    error.value = e.message || '发送失败'
  } finally {
    asking.value = false
  }
}

watch(() => route.params.sessionId, load, { immediate: true })
</script>

<template>
  <div class="live">
    <h1 class="heading">
      {{ session?.title || '直播课堂' }}
      <span v-if="session" class="page-tag">第 {{ session.currentPage }} 页</span>
    </h1>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="error" class="empty error-text">{{ error }}</div>

    <div v-else class="body">
      <section class="video">
        <div class="video-box">直播画面（占位）</div>
        <p class="hint">拉流地址：{{ session?.streamPullUrl }}</p>
      </section>

      <aside class="qa">
        <h2 class="qa-title">AI 问答助手</h2>
        <div class="qa-list">
          <div v-for="r in records" :key="r.id" class="qa-item">
            <p class="q">问：{{ r.question }}</p>
            <p class="a">答：{{ r.answer }}</p>
          </div>
          <p v-if="!records.length" class="empty">还没有问答，问点什么吧</p>
        </div>
        <div class="qa-input">
          <input v-model="question" placeholder="输入你的问题…" @keyup.enter="ask" />
          <button :disabled="asking" @click="ask">发送</button>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.live {
  max-width: 1100px;
  margin: 0 auto;
}
.heading {
  font-size: 22px;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.page-tag {
  font-size: 13px;
  color: #d97757;
  background: #fff3e6;
  padding: 3px 10px;
  border-radius: 20px;
}
.body {
  display: flex;
  gap: 16px;
}
.video {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.video-box {
  height: 420px;
  background: #222;
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
}
.hint {
  color: #999;
  font-size: 12px;
}
.qa {
  width: 380px;
  flex-shrink: 0;
  background: #fff;
  border: 1px solid #eee;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
}
.qa-title {
  font-size: 15px;
  padding: 14px 16px;
  border-bottom: 1px solid #eee;
}
.qa-list {
  flex: 1;
  padding: 16px;
  overflow-y: auto;
  max-height: 360px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.qa-item .q {
  font-size: 13px;
  color: #333;
  margin-bottom: 4px;
}
.qa-item .a {
  font-size: 13px;
  color: #666;
  background: #f7f7f8;
  border-radius: 6px;
  padding: 8px 10px;
}
.empty {
  color: #999;
  text-align: center;
  padding: 30px 0;
  font-size: 13px;
}
.error-text {
  color: #e74c3c;
}
.qa-input {
  display: flex;
  gap: 8px;
  padding: 12px;
  border-top: 1px solid #eee;
}
.qa-input input {
  flex: 1;
  padding: 9px 12px;
  border: 1px solid #ddd;
  border-radius: 6px;
  font-size: 14px;
}
.qa-input input:focus {
  outline: none;
  border-color: #d97757;
}
.qa-input button {
  padding: 9px 16px;
  border: none;
  border-radius: 6px;
  background: #d97757;
  color: #fff;
  cursor: pointer;
  font-size: 14px;
}
</style>
