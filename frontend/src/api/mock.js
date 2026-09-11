// src/api/mock.js
// 开发阶段 mock 数据：按 method + url 匹配返回假数据，不发真实请求。
//
// ⚠️ 默认关闭：即使 `npm run dev` 也走真实后端。
// 想临时看假数据，在 frontend/.env.local 里写一行：
//     VITE_USE_MOCK=true
// 然后**重启** dev server（改 .env 不会热更新）。
//
// 曾经的写法是 `import.meta.env.DEV`，导致 dev 下永远为 true——
// 前端所有请求都被本地假数据接管，上传课件根本没发到后端，
// 界面还显示「上传成功：新课件.pptx」（那个名字是写死的假数据）。

export const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

const currentUser = {
  id: 1,
  username: 'zhangsan',
  role: 'STUDENT',
  nickname: '张三',
}

const coursewareList = [
  { id: 1, name: '数据结构 · 第一章 绪论.pptx', status: 'PARSED', pageCount: 20, uploaderId: 2, uploaderName: '刘老师', uploadedAt: '2026-09-08T10:00:00' },
  { id: 2, name: '操作系统 · 进程管理.pptx', status: 'PARSED', pageCount: 35, uploaderId: 2, uploaderName: '刘老师', uploadedAt: '2026-09-09T14:30:00' },
  { id: 3, name: '计算机网络 · TCP/IP 详解.pptx', status: 'CONVERTED', pageCount: 42, uploaderId: 3, uploaderName: '王老师', uploadedAt: '2026-09-10T09:00:00' },
]

const qaRecords = [
  { id: 1, question: '这个知识点能再解释一下吗？', answer: '好的，这是针对当前页知识点的示例回答……', askedAt: '2026-09-10T10:30:00' },
]

function ok(data) {
  return { code: 0, message: 'ok', data }
}

export function getMockResponse(method, url, data, params) {
  if (!USE_MOCK) return null
  method = (method || 'get').toLowerCase()

  // 认证
  if (method === 'post' && url === '/auth/register') return ok({ ...currentUser, token: 'mock-token' })
  if (method === 'post' && url === '/auth/login') return ok({ token: 'mock-token', user: currentUser })
  if (method === 'get' && url === '/auth/me') return ok(currentUser)
  if (method === 'put' && url === '/auth/password') return ok(null)

  // 课件列表（分页 + 搜索 + 筛选）
  if (method === 'get' && url === '/courseware') {
    const { page = 1, size = 10, keyword = '', status = '' } = params || {}
    let list = coursewareList
    if (keyword) list = list.filter((c) => c.name.includes(keyword))
    if (status) list = list.filter((c) => c.status === status)
    const total = list.length
    const start = (Number(page) - 1) * Number(size)
    list = list.slice(start, start + Number(size))
    return ok({ list, total, page: Number(page), size: Number(size) })
  }

  // 课件详情 /courseware/{id}
  const cwMatch = url.match(/^\/courseware\/(\d+)$/)
  if (method === 'get' && cwMatch) {
    return ok(coursewareList.find((c) => c.id === Number(cwMatch[1])) || null)
  }

  // 课件页列表 /courseware/{id}/pages
  const pagesMatch = url.match(/^\/courseware\/(\d+)\/pages$/)
  if (method === 'get' && pagesMatch) {
    const n = Number(pagesMatch[1]) === 1 ? 20 : 10
    const pages = Array.from({ length: n }, (_, i) => ({
      id: 1000 + i,
      pageNo: i + 1,
      textContent: `第 ${i + 1} 页内容摘要……`,
      slideUrl: `/slides/${pagesMatch[1]}/page${i + 1}.html`,
    }))
    return ok({ list: pages })
  }

  // 上传课件
  if (method === 'post' && url === '/courseware/upload') {
    return ok({ id: 99, name: '新课件.pptx', status: 'UPLOADED' })
  }

  // 课堂
  if (method === 'post' && url === '/session') {
    return ok({ id: 1, coursewareId: 1, title: '数据结构 · 第3周', status: 'NOT_STARTED', streamPushUrl: 'rtmp://mock', streamPullUrl: 'http://mock/live' })
  }
  const sessionMatch = url.match(/^\/session\/(\d+)$/)
  if (method === 'get' && sessionMatch) {
    return ok({ id: 1, title: '数据结构 · 第3周', status: 'LIVE', currentPage: 3, streamPullUrl: 'http://mock/live', coursewareId: 1 })
  }

  // 问答
  if (method === 'post' && url === '/qa/ask') {
    return ok({ id: Date.now(), question: data?.question || '', answer: '（示例回答）这是针对当前页知识点的 AI 回答……', askedAt: new Date().toISOString() })
  }
  if (method === 'get' && url === '/qa/records') return ok({ list: qaRecords })

  return null
}
