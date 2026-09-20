// src/composables/useConversations.js
//
// 学生 AI 会话的增删改查（M4）。
//
// 会话归学生本人，接口靠**归属校验**保证「读不到别人的会话」——
// 前端这里不做任何权限判断，那只是体验层。

import { ref } from 'vue'
import http from '@/api/http'

export function useConversations() {
  const conversations = ref([])
  const loading = ref(false)
  const error = ref('')

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const data = await http.get('/ai/conversations')
      conversations.value = data.list || []
    } catch (e) {
      error.value = e.message || '会话列表加载失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 新建会话。三个字段全部可选：
   * 带 coursewareId 就是「针对某份课件的会话」，什么都不带就是自由问答。
   */
  async function create(payload = {}) {
    error.value = ''
    try {
      const created = await http.post('/ai/conversations', payload)
      // 放到列表最前：新会话的 lastActiveAt 是刚写的，按倒序就该排第一
      conversations.value = [created, ...conversations.value]
      return created
    } catch (e) {
      error.value = e.message || '新建会话失败'
      return null
    }
  }

  async function rename(id, title) {
    error.value = ''
    try {
      const updated = await http.put(`/ai/conversations/${id}`, { title })
      conversations.value = conversations.value.map((c) => (c.id === id ? updated : c))
      return updated
    } catch (e) {
      error.value = e.message || '重命名失败'
      return null
    }
  }

  async function remove(id) {
    error.value = ''
    try {
      await http.delete(`/ai/conversations/${id}`)
      conversations.value = conversations.value.filter((c) => c.id !== id)
      return true
    } catch (e) {
      error.value = e.message || '删除失败'
      return false
    }
  }

  return { conversations, loading, error, load, create, rename, remove }
}
