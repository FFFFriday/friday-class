// src/api/http.js
// axios 封装：统一加 token、统一解包 { code, message, data }、开发阶段走 mock。
import axios from 'axios'
import { USE_MOCK, getMockResponse } from './mock'

// timeout 是「默认值」：普通接口 10 秒足够。
// 上传课件要例外——后端解析 .pptx 是同步的（POI 逐页抽文字），
// 大课件几十秒很正常，所以 UploadPage 会单独传一个更长的 timeout。
const http = axios.create({ baseURL: '/api', timeout: 10000 })

// 请求拦截器：加 token
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 请求拦截器：开发阶段用 mock 数据，不发真实请求
http.interceptors.request.use((config) => {
  if (!USE_MOCK) return config
  const mock = getMockResponse(config.method, config.url, config.data, config.params)
  if (mock) {
    config.adapter = () =>
      Promise.resolve({ data: mock, status: 200, statusText: 'OK', headers: {}, config })
  }
  return config
})

// 响应拦截器：解包 { code, message, data }
http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code === 0) return body.data
      return Promise.reject(new Error(body.message || '请求失败'))
    }
    return body
  },
  (err) => {
    const res = err.response
    const status = res?.status
    const url = err.config?.url || ''
    const body = res?.data

    // 后端出错时响应体仍是 { code, message, data }，把 message 翻出来给用户看。
    // 否则前端只剩 “Request failed with status code 400” 这种没用的英文。
    const envelopeMessage =
      body && typeof body === 'object' && typeof body.message === 'string' && body.message
        ? body.message
        : null

    // 登录接口自己返回的 401 是「密码错」，不是「会话过期」，不能动 token、不能跳转。
    // （AuthService 用 BadCredentialsException → GlobalExceptionHandler → HTTP 401）
    // 注意只有 /auth/login 会这样：改密码用的 BusinessException 走 HTTP 200，
    // /auth/me 的 401 是真·令牌失效，必须照常处理。
    const isLoginRequest = url.startsWith('/auth/login')

    // 401：令牌无效/过期/被撤销（改密码后旧令牌全部作废）→ 清掉重新登录
    if (status === 401 && !isLoginRequest) {
      localStorage.removeItem('token')
      if (!window.location.pathname.startsWith('/login')) {
        window.location.href = '/login'
      }
      return Promise.reject(new Error(envelopeMessage || '登录已过期，请重新登录'))
    }

    // 403 要先于「透传 message」判断，否则后端那句笼统的「无权访问」
    // 会把这条更有用的提示顶掉（SecurityConfig 的 403 也是带 message 的 JSON 信封）。
    if (status === 403) {
      return Promise.reject(
        new Error(
          url.includes('/courseware/upload')
            ? '没有权限上传课件：请用教师账号登录（学生账号不能上传）'
            : envelopeMessage || '没有权限执行该操作',
        ),
      )
    }

    if (envelopeMessage) {
      return Promise.reject(new Error(envelopeMessage))
    }

    // 超时：axios 1.x 默认把 timeout 也报成 ECONNABORTED，但主动 abort 用的是同一个码，
    // 所以再加一句 message 判断，避免把用户取消说成「超时」。
    if (err.code === 'ECONNABORTED' && /timeout/i.test(err.message || '')) {
      return Promise.reject(new Error('请求超时，课件较大时解析较慢，请稍后重试'))
    }

    // 连不上后端：dev 下是 Vite 代理回一个 500 + 纯文本 body（不是信封），
    // 部署后是网关的 502/504。所以「5xx 且没信封」也按连不上处理。
    if (!res || (status >= 500 && !envelopeMessage)) {
      return Promise.reject(new Error('连不上后端服务，请确认后端已启动'))
    }

    return Promise.reject(err)
  },
)

export default http
