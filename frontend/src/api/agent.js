// src/api/agent.js
// AI 智能体接口。全部走 http.js —— token 注入、401 处理、错误信封解包都在那里。
import http from './http'

/** 我上传的课件（下拉框）。刻意不用门户的 /courseware：那个是公开的、返回所有人的。 */
export function listCoursewares() {
  return http.get('/agent/coursewares')
}

/** 我教过的课堂（下拉框）。复用既有接口，不必新增。 */
export function listTaughtSessions() {
  return http.get('/session/taught')
}

/** 我的文件夹。首次访问后端会自动建一个默认文件夹，所以不会返回空列表。 */
export function listFolders() {
  return http.get('/agent/folders')
}

/** 新建文件夹。只传名字，目录由服务端拼。 */
export function createFolder(name) {
  return http.post('/agent/folders', { name })
}

/** 输出格式白名单。从后端取，避免前后端各写一份对不上。 */
export function listFormats() {
  return http.get('/agent/formats')
}

/** 我的产出物列表，可按课堂过滤。 */
export function listFiles(sessionId) {
  return http.get('/agent/files', { params: sessionId ? { sessionId } : {} })
}

/**
 * 发起任务。**立刻返回 taskId**，真正的活在后台跑。
 *
 * 这里必须是「发起 + 轮询」而不是「等结果」：http.js 的 axios timeout 是 10 秒，
 * 而一次任务要十几秒到一分钟，同步等必然超时。
 */
export function startTask(payload) {
  return http.post('/agent/tasks', payload)
}

/** 轮询任务状态。 */
export function fetchTask(taskId) {
  return http.get(`/agent/tasks/${taskId}`)
}

/**
 * 拿到的是文件内容，还是后端的错误信封？
 *
 * <p>先看 Content-Type，**再看开头几个字节**：只信头部是不够的 ——
 * 万一某天经过的代理没带类型（blob.type 会是空串），判断就会失效，
 * 那段 JSON 会被当成文件保存下来，用户得到一个打不开的「文档」。
 *
 * <p>用正则卡死 {@code {"code":} 这个形状而不是「以 { 开头」：
 * Markdown 里完全可能有个 JSON 代码块，误判会把好文件当错误抛掉。
 */
async function looksLikeJsonEnvelope(blob) {
  if (!blob) return false
  if (blob.type && blob.type.includes('application/json')) return true
  try {
    const head = await blob.slice(0, 40).text()
    return /^\s*\{\s*"code"\s*:/.test(head)
  } catch {
    return false
  }
}

/**
 * 下载产出物。
 *
 * ⚠ 这里有个必须处理的坑：后端出错时（比如文件不属于你）返回的是
 * **HTTP 200 + JSON 信封**（本项目的业务错误约定，见 GlobalExceptionHandler）。
 * 但因为我们请求的是 blob，axios 把那段 JSON 也当成二进制收下了 ——
 * 直接保存的话，用户会下载到一个内容是 `{"code":404,...}` 的「文档」，
 * 打开才发现是乱码。
 *
 * 所以先看内容类型：是 JSON 就把它读出来、按业务错误抛出去。
 */
export async function downloadFile(file) {
  const blob = await http.get(`/agent/files/${file.id}/download`, { responseType: 'blob' })

  if (await looksLikeJsonEnvelope(blob)) {
    let message = '下载失败'
    try {
      const body = JSON.parse(await blob.text())
      message = body.message || message
    } catch {
      // 读不出来就用兜底文案，别把解析异常抛给用户
    }
    throw new Error(message)
  }

  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = file.filename || '下载'
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 立刻 revoke 会让某些浏览器来不及开始下载，挪到下一个宏任务
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
