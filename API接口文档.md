# 周五课堂 · 后端 API 接口文档

> **本文档分两部分，性质不同，读的时候注意区分**：
>
> | 部分 | 内容 | 性质 |
> |---|---|---|
> | 第一 ~ 五节 | 8 个 `/api` 接口 + 1 个静态资源接口 | ✅ **实测**，逐行读代码得出，**现在就能调通** |
> | 第六节 | 7 个尚未实现的接口 | 📐 **设计规格**，来自契约与设计文档，**代码里还没有** |
>
> **来源**：`backend/src/main/java`（controller / dto / common / config / security / service）、
> `db/schema.sql`、`docs/api-contract.md`、`docs/ai-agent-design.md`、`frontend/src`。
>
> **与 `docs/api-contract.md` 的关系**：那份是「前端倒推出来的契约」，是给后端的**任务书**；
> 本文第一节是**已完成部分的实测说明**，第六节把契约里尚未实现的部分**补成了同等详细度的规格**。
> 三方字段名经过核对一致。
>
> 最后核对时间：2026-09-14　｜　对应当前 `main` 分支代码

## 目录

| 节 | 内容 | 说明 |
|---|---|---|
| [一](#一全局约定) | 全局约定 | 端口、响应信封、状态码、认证、分页、CORS |
| [二](#二接口总表) | 接口总表 | 全部接口一览 |
| [三](#三认证模块) | 认证模块 | 注册 / 登录 / me / 改密码 |
| [四](#四课件模块) | 课件模块 | 列表 / 上传 / 详情 / 页列表 |
| [五](#五网页幻灯片静态资源) | 网页幻灯片 | `/slides/...` |
| **[六](#六尚未实现的接口完整设计规格)** | **尚未实现的接口** | **7 个接口的完整规格 + 6 个必须先修的坑** |
| [七](#七快速自测) | 快速自测 | 可直接复制粘贴的 curl |
| [八](#八前端对接速查) | 前端对接速查 | 约定与已知问题 |
| [附录 A](#附录-a错误码速查) | 错误码速查 | |
| [附录 B](#附录-b本文档的核对来源) | 核对来源 | 每个结论对应哪个文件 |
| [附录 C](#附录-c修订记录) | **修订记录** | **查出来并改掉了哪些错、哪些是实测的、哪些还没核** |

---

## 一、全局约定

### 1.1 地址与端口

| 项 | 值 | 说明 |
|---|---|---|
| 后端端口 | `8081` | 8080 被本机 nginx 占用 |
| 后端根地址 | `http://localhost:8081` | |
| API 前缀 | `/api` | **幻灯片接口例外**，见 §5 |
| 前端开发端口 | `5173` | Vite，已配 `/api`、`/slides` 两个代理指向 8081 |
| 前端 baseURL | `/api` | `frontend/src/api/http.js` 里 axios 的配置 |

前端开发时浏览器访问 `http://localhost:5173`，由 Vite 代理转发到 8081，**不产生跨域**。
若绕开 Vite 直连 8081，则受 CORS 限制（见 §1.6）。

### 1.2 统一响应信封

**所有** `/api` 接口（含报错）都返回这个结构：

```json
{
  "code": 0,
  "message": "ok",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `code` | int | `0` = 成功；非 0 = 失败（值等于 HTTP 语义码，如 400/401/403/404/500） |
| `message` | string | `code=0` 时为 `"ok"`，否则是**可直接展示给用户的中文文案** |
| `data` | any | 成功时的业务数据；失败时为 `null`；无返回值的接口成功时也是 `null` |

**前端解包逻辑**（`http.js` 响应拦截器）：`code === 0` 就 `return data`，所以页面里拿到的
直接是 `data`，**不需要**写 `res.data.data`。

```js
// 页面里长这样
const data = await http.post('/courseware/upload', formData)  // 拿到的就是 data
console.log(data.pageCount)
```

### 1.3 HTTP 状态码 vs 业务 code（**最容易踩的一条**）

本项目**故意**不全用 HTTP 状态码表达错误。分界线是「前端能不能自己处理」：

| HTTP | `code` | `message` 示例 | 什么时候出现 |
|---:|---:|---|---|
| **200** | 0 | `ok` | 成功 |
| **200** | 400 | `用户名已存在`、`文件内容不是有效的 .pptx（可能是改了扩展名的其他文件）`、`密码长度需在 6~64 之间` | **业务异常 / 参数校验失败**——这些都是「能直接给用户看」的错误 |
| **200** | 404 | `课件不存在`、`幻灯片不存在` | 业务层的「找不到」（`BusinessException(404, ...)`） |
| **401** | 401 | `未登录或登录已过期` | 没带 token / token 非法 / token 过期 / 改过密码（见 §3.4） |
| **401** | 401 | `用户名或密码错误` | **登录接口**密码错 |
| **403** | 403 | `无权访问` | 已登录但角色不够（如学生调上传） |
| **404** | 404 | `接口不存在` | 路径没匹配到任何 Controller，**且已通过认证**（未带令牌时先被拦成 401） |
| **500** | 500 | `服务器内部错误` | 未预期异常（堆栈只进日志，不外泄） |

> ⚠️ **两个反直觉点，务必注意**：
>
> 1. **`GET /api/courseware/999999` 返回的是 HTTP 200**，body 里 `code=404`。看着像「成功」，实际是失败。
>    判断成功**只看 `code`，不要只看 HTTP 状态**。前端 axios 拦截器已经按 `code` 处理了，所以页面里直接
>    `try/catch` 就是对的。
> 2. **`GET /slides/999/page1.html`（幻灯片不存在）返回的也是 HTTP 200 + JSON**，不是 404 页面。
>    如果把它的 URL 直接塞进 `<iframe>`，用户看到的是**一行 JSON 文字**，不是空白页。前端要自己判。

### 1.4 认证

| 项 | 值 |
|---|---|
| 方案 | JWT（HS256），**无状态**，服务端不存 session |
| 请求头 | `Authorization: Bearer <token>` |
| 有效期 | **7 天**（`jwt.expire-ms: 604800000`） |
| 载荷 | `sub`=用户名、`uid`=用户ID、`role`=`TEACHER`/`STUDENT`、`ver`=令牌版本号 |
| 签发时机 | 注册接口、登录接口返回的 `token` 字段 |
| 存储位置 | 前端存 `localStorage.token`，拦截器自动加到请求头 |

发请求时手动加头长这样：

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
```

**令牌校验不止验签名**——过滤器还会做两次查库确认：

1. 用户必须仍然存在且**未被软删除**（`deleted = 0`）；
2. 令牌里的 `ver` 必须等于库里的 `token_version`。

任一条不满足，令牌立即视为无效 → 401。

### 1.5 分页与数据类型约定

| 项 | 约定 |
|---|---|
| 分页参数 | `page`（**从 1 开始**）、`size` |
| 分页边界 | `page < 1` 兜底为 1；`size` 收敛到 `[1, 100]`（超过 100 按 100 算，不报错） |
| 分页响应 | `{ "list": [], "total": 0, "page": 1, "size": 10 }` |
| 不分页列表 | `{ "list": [] }` |
| 时间格式 | ISO 8601 字符串，如 `2026-09-14T20:35:00`（时区 `Asia/Shanghai`） |
| 角色枚举 | `TEACHER` / `STUDENT` |
| 课件状态 | `UPLOADED` / `CONVERTING` / `CONVERTED` / `PARSING` / `PARSED` / `FAILED` |

> `page` 对外**从 1 开始**，但 Spring Data 内部从 0 开始——转换在 `PageResult.from()` 里统一做了，
> 所以你拿到的一定是 1-based。

### 1.6 CORS 与安全响应头

**CORS**（仅对 `/api/**` 生效，`/slides/**` **没有**配 CORS）：

| 项 | 值 |
|---|---|
| 允许来源 | `http://localhost:5173` **（写死的，生产要改）** |
| 允许方法 | `GET` `POST` `PUT` `DELETE` `OPTIONS` |
| 允许请求头 | `Authorization` `Content-Type` |
| 允许携带凭证 | `true` |
| 预检缓存 | 3600 秒 |

**安全响应头**：

| 头 | 值 | 作用 |
|---|---|---|
| `X-Frame-Options` | `SAMEORIGIN` | 本站页面只能被同源 iframe 嵌套（幻灯片预览要用） |
| `Referrer-Policy` | `no-referrer` | 不往外带来源地址 |
| CSRF | **已关闭** | 纯 token 认证，无 Cookie 会话，不需要 |

---

## 二、接口总表

### 2.1 已实现（✅ 现在就能调通）

共 **8 个** `/api` 接口，另有 1 个静态资源接口。

| # | 方法 | 路径 | 权限 | 说明 |
|---:|---|---|---|---|
| 1 | POST | `/api/auth/register` | 匿名 | 注册（**只能注册学生**） |
| 2 | POST | `/api/auth/login` | 匿名 | 登录，拿 JWT |
| 3 | GET | `/api/auth/me` | 需登录 | 当前登录用户 |
| 4 | PUT | `/api/auth/password` | 需登录 | 修改密码 |
| 5 | GET | `/api/courseware` | 匿名 | 课件列表（分页/搜索/筛选） |
| 6 | POST | `/api/courseware/upload` | **仅 TEACHER** | 上传 .pptx 并同步解析 |
| 7 | GET | `/api/courseware/{id}` | 匿名 | 课件详情 |
| 8 | GET | `/api/courseware/{id}/pages` | 匿名 | 课件页列表 |
| 9 | GET | `/slides/{coursewareId}/page{pageNo}.html` | 匿名 | 单页网页幻灯片（HTML） |

**「匿名」的含义**：`SecurityConfig` 的白名单里显式放行了这几个**具体路径**（没有用 `/api/courseware/**` 通配，
避免以后新增的 GET 被一并公开）。带不带 token 都能访问；带了也不会报错。

### 2.2 尚未实现（📐 只有规格，见 [第六节](#六尚未实现的接口完整设计规格)）

**调这些接口现在都调不通**：不带令牌返回 `HTTP 401`，带合法令牌返回 `HTTP 404` + `接口不存在`
（细节见 §6.0 的表格）。

| # | 方法 | 路径 | 权限 | 功能 |
|---:|---|---|---|---|
| 10 | GET | `/api/courseware/{id}/prompt-pack` | 需登录 | F002 提示词包（一次拉全知识点） |
| 11 | GET | `/api/courseware/{id}/parse-progress` | 需登录 | F002 解析进度 |
| 12 | POST | `/api/session` | 仅 TEACHER | F003 创建课堂 |
| 13 | GET | `/api/session/{id}` | 需登录 | F003 课堂详情 |
| 14 | POST | `/api/qa/ask` | 需登录 | F004 学生提问 |
| 15 | GET | `/api/qa/records` | 需登录 | F004 问答记录 |
| 16 | WS | `/ws/page?sessionId={id}` | 需登录 | F003 翻页广播 |

> 🔴 **动手前先读第六节的 §6.1「实现前必须先把这 6 个问题处理掉」**：
> 有 6 个「数据库 / 实体 / 契约 / 看板」对不上的地方（两个实体缺字段、幂等键无处落地、版本号无来源、
> 看板来源标注错误、前端 pageId 写死），不先修掉，接口写完也是错的。

---

## 三、认证模块

### 3.1 注册

- **`POST /api/auth/register`**　权限：匿名
- **请求体**（`application/json`）

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `username` | string | ✅ | 长度 3~50 |
| `password` | string | ✅ | 长度 6~64 |
| `nickname` | string | ❌ | 最长 50 字符 |

```json
{
  "username": "zhangsan",
  "password": "123456",
  "nickname": "张三"
}
```

- **响应 `data`**（`RegisterResponse`，注意是**扁平结构**，不是嵌 `user`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 用户 ID |
| `username` | string | 用户名 |
| `role` | string | **恒为 `"STUDENT"`**（见下） |
| `nickname` | string | 昵称，可为 `null` |
| `token` | string | 注册即登录，直接可用的 JWT |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 3,
    "username": "zhangsan",
    "role": "STUDENT",
    "nickname": "张三",
    "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ6aGFuZ3NhbiJ9..."
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 用户名已存在 | 200 | 400 | `用户名已存在` |
| 用户名太短/太长 | 200 | 400 | `用户名长度需在 3~50 之间` |
| 密码太短/太长 | 200 | 400 | `密码长度需在 6~64 之间` |
| 昵称超长 | 200 | 400 | `昵称最长 50 个字符` |
| 字段缺失 | 200 | 400 | `用户名不能为空`（多个错误用 `；` 拼接） |
| 请求体不是合法 JSON | 200 | 400 | `请求体格式不正确` |

> 🔒 **设计约束（安全，不要改）**：请求体**不接收 `role` 字段**，传了也会被忽略，一律创建**学生**账号。
> 否则任何人在注册页选一下「教师」就能自助提权。
> 教师账号由管理员执行 `db/seed_teacher.sql` 预置，或注册后由管理员手工改库。
>
> 密码用 **BCrypt（强度 10）** 哈希后落库，`password` 明文**不进数据库、不进日志**。

- **curl 示例**

```bash
curl -X POST http://localhost:8081/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"zhangsan","password":"123456","nickname":"zhangsan"}'
```

> ⚠️ **Windows Git Bash 坑**：上面的 `-d '{"nickname":"张三"}'` 里带中文时，
> Git Bash 会按 GBK 发出去，存进库就是乱码。**带中文的请求体请写进文件再传**：
>
> ```bash
> # 1) 把 JSON 写进文件（用 UTF-8 编码保存）
> cat > reg.json <<'EOF'
> {"username":"zhangsan","password":"123456","nickname":"张三"}
> EOF
> # 2) 用 @文件 传
> curl -X POST http://localhost:8081/api/auth/register \
>   -H "Content-Type: application/json" \
>   --data-binary @reg.json
> ```

### 3.2 登录

- **`POST /api/auth/login`**　权限：匿名
- **请求体**

| 字段 | 类型 | 必填 |
|---|---|---|
| `username` | string | ✅ |
| `password` | string | ✅ |

```json
{ "username": "zhangsan", "password": "123456" }
```

- **响应 `data`**（`AuthResponse`，**嵌套 `user`**，与注册不同）

| 字段 | 类型 | 说明 |
|---|---|---|
| `token` | string | JWT |
| `user.id` | number | 用户 ID |
| `user.username` | string | 用户名 |
| `user.role` | string | `TEACHER` / `STUDENT` |
| `user.nickname` | string | 昵称，可为 `null` |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "token": "eyJhbGciOiJIUzI1NiJ9...",
    "user": { "id": 3, "username": "zhangsan", "role": "STUDENT", "nickname": "张三" }
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 用户名不存在 | **401** | 401 | `用户名或密码错误` |
| 密码错误 | **401** | 401 | `用户名或密码错误` |
| 账号已被软删除 | **401** | 401 | `用户名或密码错误` |
| 用户名为空 | 200 | 400 | `用户名不能为空` |
| 密码为空 | 200 | 400 | `密码不能为空` |

> 两个都为空时，`message` 会用 `；` 拼成 `用户名不能为空；密码不能为空`
> （`GlobalExceptionHandler` 把所有字段错误拼在一起，不是只报一条）。

> 🔒 用户名不存在与密码错误**返回完全相同的文案**，避免泄露「这个用户名注册没注册过」。
>
> ⚠️ **前端注意**：登录失败的 401 是「**密码错**」，不是「会话过期」。
> `http.js` 里专门判断了 `url.startsWith('/auth/login')` 来跳过清 token / 跳转逻辑，
> 否则输错密码会被莫名其妙踢回登录页（这个 bug 曾经真实出现过）。

- **curl 示例**

```bash
curl -X POST http://localhost:8081/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"teacher","password":"teacher123"}'
```

### 3.3 当前登录用户

- **`GET /api/auth/me`**　权限：**需登录**
- **请求参数**：无
- **响应 `data`**（`UserResponse`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 用户 ID |
| `username` | string | 用户名 |
| `role` | string | `TEACHER` / `STUDENT` |
| `nickname` | string | 昵称，可为 `null` |

```json
{ "code": 0, "message": "ok",
  "data": { "id": 3, "username": "zhangsan", "role": "STUDENT", "nickname": "张三" } }
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 没带 token / token 非法 / token 过期 | 401 | 401 | `未登录或登录已过期` |
| **账号已被软删除** | 401 | 401 | `未登录或登录已过期` |
| 改过密码后拿旧 token 来调 | 401 | 401 | `未登录或登录已过期` |

> 🔎 注意第二行：账号被删时，调 `/auth/me` 返回的是 **401 而不是 404**。
> 因为 JWT 过滤器要求「用户存在且未被软删除」才会写入认证信息，
> 用户被删时请求**根本进不到 Controller**，先在过滤链上就被拒了。
> （`AuthService.me` 里那句 `用户不存在` 的 404 分支实际不可达，属于兜底。）

- **用途**：前端刷新页面后用本地 token 换取最新用户信息（角色可能被管理员改过）。

- **curl 示例**

```bash
TOKEN="把登录返回的 token 粘这里"
curl http://localhost:8081/api/auth/me -H "Authorization: Bearer $TOKEN"
```

### 3.4 修改密码

- **`PUT /api/auth/password`**　权限：**需登录**
- **请求体**

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `oldPassword` | string | ✅ | 非空 |
| `newPassword` | string | ✅ | 长度 6~64 |

```json
{ "oldPassword": "123456", "newPassword": "654321" }
```

- **响应 `data`**：`null`

```json
{ "code": 0, "message": "ok", "data": null }
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 原密码为空 | 200 | 400 | `原密码不能为空` |
| 新密码为空 | 200 | 400 | `新密码不能为空` |
| 新密码长度不合法 | 200 | 400 | `新密码长度需在 6~64 之间` |
| 原密码错误 | 200 | 400 | `原密码错误` |
| 未登录 | 401 | 401 | `未登录或登录已过期` |

> 多个字段同时不合法时用 `；` 拼接，例如 `原密码不能为空；新密码长度需在 6~64 之间`。

> ⚠️ **重要副作用：改完密码，**包括当前这个** token 在内的所有旧令牌立即失效。**
> 实现方式是把 `user.token_version` 自增 1，而 JWT 里带着签发时的 `ver`，对不上就拒绝。
> 所以**改密码成功后必须重新登录**——前端 `ProfilePage` 目前只是提示了一下，
> 并没有清掉本地 token 并跳登录页，这是个已知待修项。

- **curl 示例**

```bash
curl -X PUT http://localhost:8081/api/auth/password \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"oldPassword":"123456","newPassword":"654321"}'
```

---

## 四、课件模块

### 4.1 课件列表

- **`GET /api/courseware`**　权限：匿名
- **查询参数**

| 参数 | 类型 | 必填 | 默认 | 说明 |
|---|---|---|---|---|
| `page` | int | ❌ | `1` | 页码，从 1 起；小于 1 兜底为 1 |
| `size` | int | ❌ | `10` | 每页条数；越界收敛到 `[1, 100]` |
| `keyword` | string | ❌ | — | 按**课件名**模糊匹配；空串视为不过滤；**最长 100 字符** |
| `status` | string | ❌ | — | 按状态精确筛选，取值见 §1.5；空串视为不过滤；**非法值报错** |

- **响应 `data`**（`PageResult<CoursewareResponse>`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `list[]` | array | 当页课件 |
| `list[].id` | number | 课件 ID |
| `list[].name` | string | 课件名（上传时的原始文件名，截到 200 字符） |
| `list[].status` | string | 课件状态 |
| `list[].pageCount` | number | 总页数 |
| `list[].uploaderId` | number | 上传者 ID |
| `list[].uploaderName` | string | 上传者**昵称**，没填昵称则回退成用户名 |
| `list[].uploadedAt` | string | 上传时间 |
| `total` | number | 总条数 |
| `page` | number | 当前页（1-based） |
| `size` | number | 每页条数 |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "list": [
      {
        "id": 1,
        "name": "计算机网络第8版课件-第5章-运输层.pptx",
        "status": "CONVERTED",
        "pageCount": 86,
        "uploaderId": 2,
        "uploaderName": "演示教师",
        "uploadedAt": "2026-09-14T20:35:12"
      }
    ],
    "total": 1,
    "page": 1,
    "size": 10
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| `keyword` 超 100 字符 | 200 | 400 | `搜索关键字过长（最多 100 个字符）` |
| `status` 非法值 | 200 | 400 | `课件状态不合法：xxx` |
| `page=abc` | 200 | 400 | `参数格式不正确：page` |

> 🔎 **搜索语义**：`keyword` 里的 `%`、`_`、`!` 会被**转义成字面字符**再拼进 LIKE。
> 所以搜 `%` 是搜「名字里有百分号的课件」，而不是「匹配全部」。
> 参数化绑定本身已经防住了 SQL 注入，这里处理的是**语义**问题。

- **curl 示例**

```bash
# 第 1 页，每页 5 条
curl "http://localhost:8081/api/courseware?page=1&size=5"

# 按名字搜「运输层」
curl --get --data-urlencode "keyword=运输层" http://localhost:8081/api/courseware

# 只看解析完成的
curl "http://localhost:8081/api/courseware?status=PARSED"
```

### 4.2 上传课件（教师专属）

- **`POST /api/courseware/upload`**　权限：**必须登录且角色为 `TEACHER`**
- **请求格式**：`multipart/form-data`（**不是 JSON**）

| 表单字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `file` | file | ✅ | 仅 `.pptx`，单个 ≤ **50MB**（整个请求 ≤ 55MB） |

- **服务端处理流程**（**同步执行**，请求会一直挂着直到完成）

```
校验（扩展名 → 文件头 PK → POI 真实打开）
  → 以 UUID 为名落盘（不用客户端文件名，杜绝路径穿越）
  → POI 逐页抽取文字 + 表格
  → 每页生成一个网页幻灯片 HTML，写到 slides/{课件ID}/page{N}.html
  → 写库：courseware + courseware_page
  → 状态直接置为 CONVERTED
```

- **响应 `data`**（`CoursewareUploadResponse`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 新课件 ID |
| `name` | string | 课件名（原始文件名，只取文件名部分，截到 200 字符） |
| `status` | string | **固定 `"CONVERTED"`**（页面已就绪，但**尚未做 AI 知识点解析**） |
| `pageCount` | number | 解析出的页数 |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "id": 7,
    "name": "计算机网络第8版课件-第5章-运输层.pptx",
    "status": "CONVERTED",
    "pageCount": 86
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 没带 token | 401 | 401 | `未登录或登录已过期` |
| **用学生 token** | **403** | 403 | `无权访问` |
| **请求不是 multipart**（如漏写 `-F`） | 200 | 400 | `上传请求不合法：文件缺失或超过大小限制` |
| **是 multipart 但没有 `file` 字段** | 200 | 400 | `缺少上传的文件` |
| **有 `file` 字段但是空文件** | 200 | 400 | `请选择要上传的文件` |
| 扩展名不是 .pptx | 200 | 400 | `只支持 .pptx 格式的课件` |
| 改了扩展名的假 pptx | 200 | 400 | `文件内容不是有效的 .pptx（可能是改了扩展名的其他文件）` |
| 文件 > 50MB | 200 | 400 | `文件过大，单个课件不能超过 50MB` |
| 没有可解析的幻灯片 | 200 | 400 | `课件中没有可解析的幻灯片` |
| **解析失败（含页数 > 500）** | 200 | 400 | `课件解析失败，请确认是有效的 .pptx 文件` |

> 🔴 **「没选文件」有三种形态，文案各不相同**——都是 200/400，但 `message` 不一样，前端别硬编码其中一条。
>
> **🐛 附一个代码 bug（不只是文档问题）**：`PptxConverter` 里有「页数 > 500」的检查，
> 文案本该是 `课件页数过多（N 页），最多支持 500 页`，但**这条错误永远显示不出来**：
>
> ```java
> // PptxConverter.java:65-67  ← 抛 BusinessException("课件页数过多…")
> try (...; XMLSlideShow ppt = new XMLSlideShow(in)) {
>     if (slides.size() > MAX_SLIDES) { throw new BusinessException("课件页数过多…"); }
>     ...
> } catch (IOException | RuntimeException ex) {   // :76  BusinessException 是 RuntimeException，被这里吞掉
>     throw new BusinessException("课件解析失败，请确认是有效的 .pptx 文件");  // :78  被改写成这句
> }
> ```
>
> 用户拿一份 501 页的课件，看到的会是「课件解析失败，请确认是有效的 .pptx 文件」，
> 从而**以为是文件坏了**，实际是页数超限。修法：把 `catch` 拆开，
> 让 `BusinessException` 原样抛出（`catch (IOException ex)` + 单独处理 `RuntimeException`）。

> ⚠️ **前端必须把超时调长。** 后端是**同步**解析的，几十页的课件要等十几秒到几十秒。
> `http.js` 默认 `timeout: 10000`（10 秒）**不够**，`UploadPage.vue` 里单独传了 `180000`（3 分钟）：
>
> ```js
> const data = await http.post('/courseware/upload', formData, { timeout: 180000 })
> ```
>
> 新增任何上传入口时，**别忘了这个 timeout**，否则大课件必然「莫名超时」。

> 🔒 **权限为什么配在 `SecurityConfig` 而不是只写 `@PreAuthorize`**：
> `@PreAuthorize` 是方法级 AOP，**等 Controller 方法被调用了才拦**，而这时 multipart 早就解析完、文件已经写进磁盘了。
> 学生拿合法令牌狂传大文件，文件先落盘再被 403 拒 → **能撑爆磁盘**。
> 所以在过滤链上加了 `.requestMatchers(POST, "/api/courseware/upload").hasRole("TEACHER")`，
> 让拒绝发生在**读 body 之前**。Controller 上的注解保留作纵深防御。

- **curl 示例**

```bash
TOKEN="教师账号登录拿到的 token"

curl -X POST http://localhost:8081/api/courseware/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@/c/Users/Friday/Desktop/作业/计算机网络/运输层.pptx"
```

> 💡 **Git Bash 路径坑**：Windows 路径要写成 `/c/...` 或加引号，**不要**写 `C:\...`（反斜杠会被吃掉）。
> 也可以先 `cd` 到文件所在目录，再用 `-F "file=@运输层.pptx"`。

### 4.3 课件详情

- **`GET /api/courseware/{id}`**　权限：匿名
- **路径参数**：`id`（number）
- **响应 `data`**：`CoursewareResponse`，字段同 §4.1 的 `list[]` 元素

```json
{
  "code": 0, "message": "ok",
  "data": {
    "id": 1, "name": "运输层.pptx", "status": "CONVERTED", "pageCount": 86,
    "uploaderId": 2, "uploaderName": "演示教师", "uploadedAt": "2026-09-14T20:35:12"
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 课件不存在或已软删除 | **200** | 404 | `课件不存在` |
| `id` 不是数字 | 200 | 400 | `参数格式不正确：id` |

> ⚠️ 注意「不存在」返回的是 **HTTP 200**，前面 §1.3 说过。

### 4.4 课件页列表

- **`GET /api/courseware/{id}/pages`**　权限：匿名
- **路径参数**：`id`（课件 ID）
- **响应 `data`**（`ListResult<CoursewarePageResponse>`）——**不分页**

| 字段 | 类型 | 说明 |
|---|---|---|
| `list[].id` | number | **页 ID（`pageId`）**，学生端提问时要传的就是它 |
| `list[].pageNo` | number | 页码，从 1 起 |
| `list[].textContent` | string | 该页抽取出的文字（可能为空串，代表这页没文字） |
| `list[].slideUrl` | string | 网页幻灯片地址，**可直接访问**，如 `/slides/1/page1.html` |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "list": [
      { "id": 101, "pageNo": 1, "textContent": "第5章 运输层\n本章重点……", "slideUrl": "/slides/1/page1.html" },
      { "id": 102, "pageNo": 2, "textContent": "", "slideUrl": "/slides/1/page2.html" }
    ]
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 课件不存在 | 200 | 404 | `课件不存在` |
| `id` 不是数字（如 `/courseware/abc/pages`） | 200 | 400 | `参数格式不正确：id` |

> 📌 **本接口不含知识点与预置提问**（那是 F002 的 `prompt-pack` 接口，尚未实现）。
> 别指望从这里拿 AI 上下文。

---

## 五、网页幻灯片（静态资源）

### `GET /slides/{coursewareId}/page{pageNo}.html`

- **权限**：匿名
- **路径**：注意是 **page{n}.html**，中间没有斜杠

| 路径参数 | 说明 |
|---|---|
| `coursewareId` | 课件 ID |
| `pageNo` | 页码，从 1 起 |

- **响应**：`Content-Type: text/html; charset=UTF-8`，body 是完整 HTML 文档

- **响应头**

| 头 | 值 | 说明 |
|---|---|---|
| `Content-Security-Policy` | `sandbox; default-src 'none'; style-src 'unsafe-inline'` | **沙箱**：让页面拿不到本站 Cookie / localStorage |
| `X-Content-Type-Options` | `nosniff` | 禁止类型嗅探 |

- **错误**：三种情况，**返回的 HTTP 状态不一样**，说明为什么必须看 `code`

| 情况 | 举例 | HTTP | `code` | `message` |
|---|---|---:|---:|---|
| 模板匹配，但幻灯片文件不存在 | `/slides/1/page1.html` | **200** | 404 | `幻灯片不存在` |
| `coursewareId` 不是数字 | `/slides/abc/page1.html` | 200 | 400 | `参数格式不正确：coursewareId` |
| `pageNo` 不是数字**或是空串** | `/slides/1/pagex.html`、`/slides/1/page.html` | 200 | 400 | `参数格式不正确：pageNo` |
| 路径压根不匹配模板 | `/slides/1/nope.html` | **404** | 404 | `接口不存在` |

> 上表四种情况**都用真实请求验证过**（2026-09-14）。
>
> ⚠️ 第 1 行最反直觉：**幻灯片不存在时返回的是 HTTP 200 + 一段 JSON**。
> 直接把这个 URL 塞进 `<iframe>`，用户看到的是**一行 JSON 文字**，不是空白页。
> **所以前端判断「幻灯片加载出来没有」，不能只看 HTTP 状态。**
>
> ⚠️ 第 3 行也反直觉：`/slides/1/page.html` 的 `{pageNo}` **匹配到了空字符串**，
> 于是走类型转换失败 → 400，**不是** 404。别照着「页码为空所以找不到」去想。

> 🔒 **为什么加 CSP sandbox**：这个 HTML 是由**用户上传的课件**转换来的，属于不可信内容。
> 虽然生成时做了 HTML 转义，但万一以后转义回归（比如有人改成 `v-html` 或改错了 `escapeHtml`），
> 沙箱能兜住——`default-src 'none'` 让页面里的脚本完全跑不起来。
>
> ⚠️ **前端注意**：这个接口**不在 `/api` 下**，也没有配 CORS。
> 开发时靠 Vite 的 `/slides` 代理访问；**如果新增部署环境，代理规则要跟着配**，
> 否则请求会落到前端自己身上、被 SPA 兜底返回 `index.html`，iframe 里就是一片空白。

- **curl 示例**

```bash
curl -i "http://localhost:8081/slides/1/page1.html"
```

---

## 六、尚未实现的接口（完整设计规格）

### 6.0 阅读须知：本节与前面五节的性质不同

| | 第一 ~ 五节 | **本节** |
|---|---|---|
| 来源 | 逐行读代码得出 | `docs/api-contract.md` + `docs/ai-agent-design.md` + `db/schema.sql` |
| 性质 | **实测**，现在就能调通 | **设计规格**，代码尚未实现 |
| 可信度 | 与代码逐字核对过 | 与契约/设计文档一致，但**实现时可能有微调** |

**调用本节任何接口，现在都调不通**——因为后端没有任何对应的 Controller。但**报什么错取决于你有没有带令牌**：

| 你怎么调 | 实际返回 | 为什么 |
|---|---|---|
| **不带 token** | **HTTP 401** + `{"code":401,"message":"未登录或登录已过期"}` | `SecurityConfig` 是「拒绝优先」，白名单之外一律先要认证。**认证失败就停在过滤链，压根走不到「找 Controller」那一步** |
| 带合法 token | **HTTP 404** + `{"code":404,"message":"接口不存在"}` | 认证过了，才发现没有匹配的 Controller → `NoResourceFoundException` |

> ⚠️ 这个区别值得记一下：**不带令牌时看到的 401 会让人误以为「接口存在，只是要登录」**，
> 于是跑去登录、再调、才看到 404，白折腾一轮。

规格的来源逐接口标注：

| 标记 | 含义 |
|---|---|
| 📄 契约 | 字段与语义来自 `docs/api-contract.md`，已定稿 |
| 🧠 设计 | 行为与降级策略来自 `docs/ai-agent-design.md`（V2，经架构审查） |
| ➕ 本文件补 | 契约与设计文档**都没有定义**，由本文件按数据库结构补出，**需要确认后再实现** |

---

### 6.1 ⚠️ 实现前必须先把这 6 个问题处理掉

这一节是本文件在核对「数据库 / 实体 / 契约 / 看板」四方时发现的**真实不一致**。
不先解决，接口写完也是错的。

| # | 问题 | 证据 | 后果 | 建议 |
|---:|---|---|---|---|
| **P1** | `class_session.current_page` **建表脚本里有，JPA 实体里没有** | `db/schema.sql:101` 有 `current_page INT`；`entity/ClassSession.java` 全文无 `currentPage` | 翻页广播「先落库再广播」**落不进去**——Hibernate 根本不认识这一列，`current_page` 永远是 `NULL`，迟到/重连的学生拿不到页码 | 给 `ClassSession` 加 `private Integer currentPage;`（`@Column(name = "current_page")`） |
| **P2** | `qa_record.status` **同上** | `db/schema.sql:123` 有 `status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS'`；`entity/QaRecord.java` 全文无 `status` | 契约 §4.1 要返回 `status`，但实体读不出来；F006 学情统计「只算 SUCCESS」无法实现——因为所有记录看起来都一样 | 给 `QaRecord` 加 `private String status;`（或用枚举 `QaStatus`） |
| **P3** | 幂等键 `clientRequestId` **库里没有列** | 契约 §4.1 与设计文档 §3.6 都要求「`qa_record` 对其建唯一索引」；`db/schema.sql` 的 `qa_record` **没有这一列**，也没有唯一索引 | 设计文档承诺的幂等**无处落地**；学生重复点「发送」会重复调用模型、重复入库（费钱 + 统计失真） | 加列 `client_request_id VARCHAR(64) NULL` + 唯一索引 `uk_qa_client_req`；或明确**放弃幂等**并改文档 |
| **P4** | `parseVersion` **库里没有列** | 契约 §2.5 返回 `parseVersion` 并靠它判断缓存失效；`courseware` 表**没有** `parse_version` | 缓存失效机制没有依据 | 不必新增列：用**该课件最近一次成功任务的 `ai_parse_task.id`** 当版本号即可（单调递增，语义正好）。契约里不用改字段名 |
| **P5** | `GET /api/courseware/{id}/parse-progress` **只存在于看板** | `项目看板.md:86` 标注来源为「设计文档」，但 `grep` 全仓库，`docs/api-contract.md` 和 `docs/ai-agent-design.md` **都没有这个接口** | 看板的来源标注**是错的**；同时异步解析确实需要一个查进度的接口，否则前端只能干等 | 看板改用词，并采纳本文件 §6.3 补出的规格 |
| **P6** | 前端 `pageId` **写死为 1** | `frontend/src/pages/LivePage.vue:34` 是 `pageId: 1,` | 无论老师翻到第几页，AI **永远按第 1 页回答**。这是 F004 上线后必然暴露的 bug | 改成从 `session.currentPage` 反查 `pageId`（用 prompt-pack 的 `pageNo → pageId` 索引） |

> 另有两处**不是错误、但要提前知道**：
>
> - **`/ws` 要同步加 Vite 代理**，且必须带 `ws: true`。否则 WebSocket 请求落到 Vite 自己身上被 SPA 兜底。
>   （教训来源：`/slides` 曾经就漏了代理，见 `frontend-gotchas` 记忆。）
> - **`spring-boot-starter-websocket` 依赖已就位**（`backend/pom.xml` 里已有），
>   但代码里**没有任何** `WebSocketConfig` / `Handler`，是纯新增。

---

### 6.2 接口清单

| # | 方法 | 路径 | 权限 | 功能 | 来源 |
|---:|---|---|---|---|---|
| 1 | GET | `/api/courseware/{id}/prompt-pack` | 需登录 | F002 | 📄 契约 2.5　🧠 设计 §3.2 |
| 2 | GET | `/api/courseware/{id}/parse-progress` | 需登录 | F002 | ➕ 本文件补（见 P5） |
| 3 | POST | `/api/session` | 仅 TEACHER | F003 | 📄 契约 3.1 |
| 4 | GET | `/api/session/{id}` | 需登录 | F003 | 📄 契约 3.2 |
| 5 | POST | `/api/qa/ask` | 需登录 | F004 | 📄 契约 4.1　🧠 设计 §3.3~3.8 |
| 6 | GET | `/api/qa/records` | 需登录 | F004 | 📄 契约 4.2 |
| 7 | WS | `/ws/page?sessionId={id}` | 需登录 | F003 | 📄 契约 5.1 |

**已建好但还没用上的地基**（只差 Controller 与 Service）：

- **表**：`class_session`、`qa_record`、`knowledge_point`、`preset_question`、`course_summary`、`ai_parse_task`（`db/schema.sql` 共 9 张）
- **实体**：`ClassSession`、`QaRecord`、`KnowledgePoint`、`PresetQuestion`、`CourseSummary`、`AiParseTask`（注意 P1/P2 的缺字段）
- **Repository**：6 个全都在，方法名直接可用（`findByPageIdOrderBySortOrderAsc`、`findBySessionIdOrderByAskedAtAsc` 等）
- **依赖**：`spring-boot-starter-websocket` 已在 `pom.xml`

---

### 6.3 解析进度

> ➕ **来源：本文件补**。契约与设计文档都没定义，但看板提到了它（见 P5）。
> 字段直接照 `ai_parse_task` 表设计。

- **`GET /api/courseware/{id}/parse-progress`**　权限：**需登录**
- **路径参数**：`id`（课件 ID）
- **用途**：教师上传后轮询进度；学生进课堂前判断是否解析完成

| 字段 | 类型 | 说明 |
|---|---|---|
| `coursewareId` | number | 课件 ID |
| `coursewareStatus` | string | 课件状态：`UPLOADED`/`CONVERTING`/`CONVERTED`/`PARSING`/`PARSED`/`FAILED` |
| `taskId` | number \| null | 最近一次解析任务 ID；**从未解析过则为 `null`** |
| `status` | string \| null | 任务状态：`PENDING`/`RUNNING`/`SUCCESS`/`FAILED`/`PARTIAL` |
| `currentPage` | number | 已处理到的页（0 表示还没开始） |
| `totalPages` | number \| null | 总页数 |
| `progress` | number | 百分比，0~100，由 `currentPage / totalPages` 算出，前端可直接用来画进度条 |
| `errorMessage` | string \| null | 失败原因（`FAILED` 时有值） |
| `startedAt` / `finishedAt` | string \| null | 起止时间 |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "coursewareId": 7,
    "coursewareStatus": "PARSING",
    "taskId": 12,
    "status": "RUNNING",
    "currentPage": 18,
    "totalPages": 86,
    "progress": 20,
    "errorMessage": null,
    "startedAt": "2026-09-14T21:02:11",
    "finishedAt": null
  }
}
```

- **错误**：课件不存在 → `code=404` `课件不存在`
- **前端轮询建议**：解析中每 **2~3 秒**查一次；`status` 变为 `SUCCESS`/`FAILED`/`PARTIAL` 后**停止轮询**。

---

### 6.4 提示词包（prompt-pack）

> 📄 契约 2.5　🧠 设计 §3.2

- **`GET /api/courseware/{id}/prompt-pack`**　权限：**需登录**
- **用途**：学生进课堂时**一次拉全**该课件所有页的知识点与预置提问，按 **`pageId`** 建索引缓存。
  有了它，翻页与提问都不用再查库。

| 字段 | 类型 | 说明 |
|---|---|---|
| `coursewareId` | number | 课件 ID |
| `parseVersion` | number | **解析版本号**，见下方说明 |
| `parseStatus` | string | 课件当前状态（`PARSING`/`PARSED`/`FAILED`…）➕**本文件补**，理由见下 |
| `pages[]` | array | 全部页，按 `pageNo` 升序 |
| `pages[].pageId` | number | **页 ID，缓存键必须用它** |
| `pages[].pageNo` | number | 页码 |
| `pages[].knowledgePoints` | string[] | 知识点，**无内容时是空数组 `[]`，不是 `null`** |
| `pages[].presetQuestions` | string[] | 预置提问，同上 |

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "coursewareId": 1,
    "parseVersion": 12,
    "parseStatus": "PARSED",
    "pages": [
      {
        "pageId": 101,
        "pageNo": 1,
        "knowledgePoints": ["运输层的作用", "复用与分用的区别"],
        "presetQuestions": ["为什么说运输层是端到端的？"]
      },
      { "pageId": 102, "pageNo": 2, "knowledgePoints": [], "presetQuestions": [] }
    ]
  }
}
```

**三个必须遵守的约定**（都来自设计文档，写错会出静默 bug）：

| 约定 | 说明 |
|---|---|
| 🔴 **缓存键用 `pageId`，不能用 `pageNo`** | 用 `pageNo` 的话，两份课件的「第 1 页」会互相覆盖 → 引用到**错误页的知识点**。属静默错误，极难排查 |
| 🔴 **`parseVersion` 变大即丢弃旧缓存** | 场景：学生进课堂时解析还没跑完 → 缓存下来的是空知识点 → 永久为空。所以客户端要存下 `parseVersion`，发现变大就重拉 |
| 🟡 **`parseStatus` 用于区分「还在解析」与「本页确实没内容」** | 设计文档 §3.5 明确要求区分三种「没有」。**契约里原本没有这个字段**，是本文件补的——不加的话前端只能一律提示「本页暂无内容」，会在解析中误导学生 |

**`parseVersion` 怎么来**（见 P4）：库里没这一列，用**该课件最近一次成功任务的 `ai_parse_task.id`** 即可，单调递增、语义正好。

- **错误**：课件不存在 → `code=404` `课件不存在`

---

### 6.5 创建课堂（教师）

> 📄 契约 3.1

- **`POST /api/session`**　权限：**仅 TEACHER**
- **请求体**

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `coursewareId` | number | ✅ | 必须存在且**已解析完成** |
| `title` | string | ❌ | 最长 200 字；缺省时用课件名 |

```json
{ "coursewareId": 1, "title": "计算机网络 · 第5章 运输层" }
```

- **响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 课堂 ID |
| `coursewareId` | number | 课件 ID |
| `title` | string | 课堂名 |
| `status` | string | 初始恒为 `"NOT_STARTED"` |
| `teacherId` / `teacherName` | number / string | 授课教师 |
| `currentPage` | number \| null | 初始为 `null`（还没翻过页） |
| `streamPushUrl` / `streamPullUrl` | string \| null | **直播推流方案未定，MVP 阶段返回 `null`**（表中为预留字段） |

```json
{
  "code": 0, "message": "ok",
  "data": {
    "id": 1, "coursewareId": 1, "title": "计算机网络 · 第5章 运输层",
    "status": "NOT_STARTED", "teacherId": 2, "teacherName": "演示教师",
    "currentPage": null, "streamPushUrl": null, "streamPullUrl": null
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 学生调用 | **403** | 403 | `无权访问` |
| 未登录 | 401 | 401 | `未登录或登录已过期` |
| 课件不存在 | 200 | 404 | `课件不存在` |
| 课件还没解析完 | 200 | 400 | `课件尚未解析完成，无法开课` |
| `title` 超 200 字 | 200 | 400 | `课堂名称最长 200 个字符` |

> 📌 权限要**同时**配在 `SecurityConfig`（`/api/session` 的 POST）和 `@PreAuthorize`。
> 虽然这里没有文件上传、不存在「先落盘再拒绝」的问题，但保持一致更省心。

---

### 6.6 课堂详情（学生加入）

> 📄 契约 3.2

- **`GET /api/session/{id}`**　权限：**需登录**
- **响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 课堂 ID |
| `coursewareId` | number | 课件 ID（学生端靠它拉 prompt-pack） |
| `coursewareName` | string | 课件名 |
| `title` | string | 课堂名 |
| `status` | string | `NOT_STARTED` / `LIVE` / `ENDED` |
| `currentPage` | number \| null | **可能为 `null`**（老师还没翻过页）→ 前端用 `?? 1` 兜底 |
| `streamPullUrl` | string \| null | 拉流地址（MVP 为 `null`，前端显示「直播画面（占位）」） |
| `teacherName` | string | 教师昵称 |

```json
{
  "code": 0, "message": "ok",
  "data": {
    "id": 1, "coursewareId": 1, "coursewareName": "运输层.pptx",
    "title": "计算机网络 · 第5章 运输层", "status": "LIVE",
    "currentPage": 3, "streamPullUrl": null, "teacherName": "演示教师"
  }
}
```

- **错误**：课堂不存在 → `code=404` `课堂不存在`

> 🔴 **这是「断线重连恢复上下文」的唯一途径**。学生掉线重连后，WebSocket 不会补发历史消息，
> 必须调本接口拿 `currentPage` 才能对齐老师当前讲到哪。
> 所以 `currentPage` **一定要真正落库**——这就是 P1 必须先修的原因。

> ⚠️ **已知取舍**：目前**没有选课/成员表**，任何登录用户都能查任意课堂（设计文档 §7 的 M7，MVP 接受）。

---

### 6.7 学生提问

> 📄 契约 4.1　🧠 设计 §3.3~3.8

- **`POST /api/qa/ask`**　权限：**需登录**
- **请求体**

| 字段 | 类型 | 必填 | 约束 |
|---|---|---|---|
| `sessionId` | number | ✅ | 课堂 ID |
| `pageId` | number | ✅ | **当前页的页 ID**（不是 `pageNo`！），且必须属于该课堂的课件 |
| `question` | string | ✅ | **1~500 字** |
| `clientRequestId` | string | ❌ | 幂等键，客户端生成（如 UUID）；见 P3 |

```json
{
  "sessionId": 1,
  "pageId": 103,
  "question": "复用和分用到底有什么区别？",
  "clientRequestId": "b7f2c1a0-3e4d-4f5a-9c8b-1d2e3f4a5b6c"
}
```

- **响应 `data`**

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | number | 问答记录 ID |
| `pageId` | number | 提问时所在页 |
| `question` | string | 问题原文 |
| `answer` | string | AI 回答；**`status=FAILED` 时是友好提示文案**，不是报错 |
| `status` | string | `SUCCESS` 已作答 / `FAILED` 调用失败（**依赖 P2 先补字段**） |
| `askedAt` | string | 提问时间 |

```json
{
  "code": 0, "message": "ok",
  "data": {
    "id": 1, "pageId": 103,
    "question": "复用和分用到底有什么区别？",
    "answer": "复用是发送方把多个应用的数据合到一条连接上，分用是接收方再按端口拆开还回去……",
    "status": "SUCCESS",
    "askedAt": "2026-09-14T21:15:03"
  }
}
```

- **错误**

| 场景 | HTTP | `code` | `message` |
|---|---:|---:|---|
| 问题为空 | 200 | 400 | `问题不能为空` |
| 问题超 500 字 | 200 | 400 | `问题最长 500 个字符` |
| 课堂不存在 | 200 | 404 | `课堂不存在` |
| `pageId` 不属于该课件 | 200 | 400 | `页码与当前课堂不匹配` |
| **提问过快**（限流） | 200 | 429 | `提问太快了，请稍等几秒再问` |
| AI 调用超时/失败 | **200** | 0 | **`status=FAILED`，`answer` 是「AI 助教暂时忙不过来，请稍后再试」** |

> 🔴 **注意最后一行**：模型调用失败**不是 HTTP 错误**，而是**成功响应里带 `status=FAILED`**。
> 前端不能只靠 `catch` 判断失败，还得看 `status`。

**四种「没有答案」要分开提示**（🧠 设计 §3.5，这是 V2 修正的重点）：

| 情况 | 学生看到 | 是否调模型 |
|---|---|---|
| 课件还在解析 | 「课件还在解析中，稍后再试」 | ❌ |
| 课件解析失败 | 「本页内容解析失败，请告诉老师」 | ❌ |
| 本页确实没有知识点 | 「本页暂无解析内容，可先听老师讲解」 | ❌ |
| 调用超时/失败 | 「AI 助教暂时忙不过来，请稍后再试」 | ✅（失败后） |

> V1 曾把这四种统一成「本页暂无解析内容」，会在解析中误导学生。

**其他设计约定**（🧠 设计 §3.4 / 3.7 / 3.8）：

| 项 | 约定 |
|---|---|
| 响应预算 | **总 ≤ 20 秒**（单次超时 8 秒，最多重试 1 次退避 1 秒），到点返回降级文案 |
| 限流 | 每学生 **1 问 / 5 秒**，超限返回上面那条 429 |
| 去重缓存 | `(sessionId, pageId, 归一化问题)` 短 TTL（10 分钟）缓存 + single-flight，同一问题多人问只调一次模型 |
| 事务边界 | **模型调用在事务外**；结果与写库在同一个短事务里（与上传接口同一个道理——别占着连接等模型） |
| 注入防护 | 学生问题与知识点都要包进 `<<<QUESTION>>>` 之类的数据区，并**剥离定界符**；前端**禁用 `v-html`** |

---

### 6.8 问答记录

> 📄 契约 4.2

- **`GET /api/qa/records`**　权限：**需登录**
- **查询参数**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `sessionId` | number | ✅ | 课堂 ID |
| `pageId` | number | ❌ | 只看某一页的问答（老师翻回上一页时有用） |

- **响应 `data`**（`ListResult`，**不分页**）

| 字段 | 类型 | 说明 |
|---|---|---|
| `list[].id` | number | 记录 ID |
| `list[].pageId` / `pageNo` | number | 问的是哪一页 |
| `list[].question` / `answer` | string | 问答内容 |
| `list[].status` | string | `SUCCESS` / `FAILED` |
| `list[].askedAt` | string | 提问时间 |

```json
{
  "code": 0, "message": "ok",
  "data": {
    "list": [
      { "id": 1, "pageId": 103, "pageNo": 3, "question": "复用和分用……",
        "answer": "……", "status": "SUCCESS", "askedAt": "2026-09-14T21:15:03" }
    ]
  }
}
```

- **错误**：课堂不存在 → `code=404` `课堂不存在`
- **排序**：按 `askedAt` 升序（对应 `findBySessionIdOrderByAskedAtAsc`）

> ⚠️ **已知取舍**：无成员关系表 → **任何登录用户都能读任意课堂的全部问答记录**（含别人的提问）。
> MVP 接受（设计文档 M7），若要收紧需补选课表。**这是隐私相关项，上线前应重新评估**。

---

### 6.9 翻页页码广播（WebSocket）

> 📄 契约 5.1

- **连接地址**：`ws://localhost:8081/ws/page?sessionId=1`
  生产为 `wss://<域名>/ws/page?sessionId=1`
- **鉴权**：**用 `Authorization: Bearer <token>` 请求头**（或连接后的第一帧 `{"type":"auth","token":"..."}`）

> 🔴 **不要照抄契约里的 `&role=TEACHER|STUDENT`**。契约 5.1 写的是
> `ws/page?sessionId=1&role=TEACHER`，但 **`role` 由客户端自报，等于没鉴权**——
> 学生只要把 URL 里的 `role` 改成 `TEACHER`，就能广播**假页码**，
> 把全班学生的 AI 上下文带偏。角色必须从**服务端校验过的 JWT** 里取。

**消息协议**

| 方向 | 消息 | 说明 |
|---|---|---|
| 教师 → 服务端 | `{"type":"page","pageNo":3}` | 老师翻页 |
| 服务端 → 该课堂全体 | `{"type":"page","pageNo":3,"serverTime":"2026-09-14T21:20:00"}` | 广播 |
| 双向 | `{"type":"ping"}` / `{"type":"pong"}` | 心跳（建议 30 秒） |
| 服务端 → 单端 | `{"type":"error","message":"没有权限广播页码"}` | 学生试图发 `page` 时 |

**服务端处理顺序（顺序不能反）**

```
1. 收到教师的 {"type":"page","pageNo":3}
2. 先写库：class_session.current_page = 3        ← 必须先落库（依赖 P1 先补实体字段）
3. 再广播给该 session 下所有连接（含教师自己，便于多端同步）
```

> **为什么必须先落库**：迟到进入或断线重连的学生错过了广播，只能靠
> `GET /api/session/{id}` 的 `currentPage` 恢复上下文。先广播后落库的话，
> 那个窗口期内重连的学生会拿到**旧页码**。

**前端重连策略建议**

| 项 | 建议 |
|---|---|
| 重连退避 | 1s → 2s → 4s → 8s，上限 30s，避免雪崩 |
| 重连成功后 | **立刻调 `GET /api/session/{id}`** 拿 `currentPage` 补上下文（WS 不会补发历史消息） |
| 学生端只收不发 | 学生发 `page` 消息应被服务端拒绝并回 `error` |

> ⚠️ **Vite 代理要新增 `/ws` 且带 `ws: true`**：
>
> ```js
> '/ws': { target: 'ws://localhost:8081', ws: true }
> ```
>
> 漏了的话 WebSocket 请求会被 SPA 兜底处理，连不上。

---

### 6.10 建议的实现顺序

按「能不能独立验证」排序，先把不依赖模型的部分做掉：

| 顺序 | 内容 | 依赖模型？ | 说明 |
|---:|---|---|---|
| 0 | **修 P1 / P2**（给两个实体补字段） | — | 后面所有事的先决条件 |
| 1 | `POST /api/session` + `GET /api/session/{id}` | ❌ | 纯 CRUD，能立刻用 curl 验证 |
| 2 | **WebSocket 翻页广播** | ❌ | 不依赖直播推流方案，`streamPullUrl` 留 `null` 即可 |
| 3 | `GET /api/courseware/{id}/prompt-pack` | ❌ | 表里有数据就能返回；没数据就是空数组 |
| 4 | `parse-progress` | ❌ | 依赖 `ai_parse_task`，可与上一步一起做 |
| 5 | **F002 解析智能体** | ✅ | 🛑 卡在 DeepSeek API Key |
| 6 | `POST /api/qa/ask` + `GET /api/qa/records` | ✅ | 🛑 同上 |

> **关于 DeepSeek Key 的处理约定（已确认）**：F002 / F004 的**模型调用处先用假数据 stub**
> 把前后端链路跑通，之后再换成真实调用。
>
> ⚠️ 实施时必须做到两点，否则会踩上次 `USE_MOCK` 那个坑（界面显示"上传成功"、实际一个请求都没发）：
> 1. **stub 要有明显的运行日志**（如 `WARN ai_stub_used pageId=103`），不能静默；
> 2. **stub 不能被误当成已完工**——代码里留 `// TODO(stub): 换成真实 DeepSeek 调用`，
>    并在 `项目看板.md` 上明确标注「当前为 stub，未接真模型」。



---

## 七、快速自测

按顺序跑一遍，能验证整条主链是否通：

```bash
BASE=http://localhost:8081

# 0) 健康检查：随便调一个有权限的接口，能返回 JSON 就说明后端活着
curl -s "$BASE/api/courseware?size=1"
# 期望：{"code":0,"message":"ok","data":{"list":[...],"total":N,"page":1,"size":1}}

# 1) 教师登录（账号由 db/seed_teacher.sql 预置）
curl -s -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username":"teacher","password":"teacher123"}'
# 期望：{"code":0,...,"data":{"token":"eyJ...","user":{...role":"TEACHER"...}}}

# 2) 把 token 存进变量（手动粘贴上一步的 token）
TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# 3) 查当前用户，验证 token 有效
curl -s "$BASE/api/auth/me" -H "Authorization: Bearer $TOKEN"
# 期望：{"code":0,...,"data":{"id":N,"username":"teacher","role":"TEACHER","nickname":"演示教师"}}
# 注意 id 别写死：全新库上 seed_teacher.sql 只插一行，teacher 的 id 会是 1

# 4) 上传课件（注意 -F 和 @）
curl -s -X POST "$BASE/api/courseware/upload" \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@运输层.pptx"
# 期望：{"code":0,...,"data":{"id":N,"status":"CONVERTED","pageCount":M}}

# 5) 看课件页（把 N 换成上一步的 id）
curl -s "$BASE/api/courseware/N/pages"
# 期望：{"code":0,...,"data":{"list":[{"id":..,"pageNo":1,"slideUrl":"/slides/N/page1.html"}]}}

# 6) 打开第 1 页幻灯片，应该返回 HTML 源码
curl -s "$BASE/slides/N/page1.html" | head -20
```

**负面用例（验证权限确实生效）**：

```bash
# 学生调上传 → 必须 403
curl -s -i -X POST "$BASE/api/courseware/upload" \
  -H "Authorization: Bearer $STUDENT_TOKEN" \
  -F "file=@运输层.pptx" | head -1
# 期望：HTTP/1.1 403

# 不带 token 调上传 → 必须 401
curl -s -i -X POST "$BASE/api/courseware/upload" -F "file=@运输层.pptx" | head -1
# 期望：HTTP/1.1 401

# 传 .txt 冒充课件 → 400
curl -s -X POST "$BASE/api/courseware/upload" \
  -H "Authorization: Bearer $TOKEN" -F "file=@把txt改成pptx.pptx"
# 期望：{"code":400,"message":"文件内容不是有效的 .pptx（可能是改了扩展名的其他文件）"}
```

---

## 八、前端对接速查

| 项 | 做法 |
|---|---|
| 引入 http | `import http from '@/api/http'` |
| 发请求 | `const data = await http.get('/courseware')` ← **直接就是 `data`，不用解包** |
| 拿 token | `localStorage.getItem('token')` |
| 存 token | `localStorage.setItem('token', data.token)` |
| 出错处理 | `try { ... } catch (e) { e.message }` ← `message` 已是中文文案 |
| 上传超时 | `http.post('/courseware/upload', fd, { timeout: 180000 })` |
| 401 自动跳转 | 拦截器已处理（**登录接口本身除外**） |
| 判断失败 | 看 `code`，**不要看 HTTP 状态**（业务错误也是 HTTP 200） |
| 渲染 AI 回答 | **纯文本插值，禁用 `v-html`**（防 XSS） |

### 8.1 前端已知待修项（与接口相关）

| # | 位置 | 问题 | 影响 |
|---:|---|---|---|
| 1 | `LivePage.vue:34` | `pageId: 1` **写死** | AI 永远按第 1 页回答，老师翻页无效。**F004 上线后必然暴露** |
| 2 | `LivePage.vue:22` / `:40` | 加载与提问**共用一个 `error` ref** | 一次提问失败会让整页变成错误态（直播画面、问答列表一起消失） |
| 3 | `LivePage.vue` | **还没有 WebSocket** | 翻页不会自动同步，`currentPage` 只在首次加载时取一次 |
| 4 | `CoursewareDetailPage.vue` | 只把 `slideUrl` 当**文本打印**，没上 `<iframe>` | 网页幻灯片已生成但没展示出来。Vite 的 `/slides` 代理是「提前铺路」 |
| 5 | `LivePage.vue` | 提问失败时**没有区分 `status=FAILED`** | `answer` 是降级文案但被当成正常回答显示 |
| 6 | `ProfilePage.vue:33-36` | 改密码成功后**没清 token、没跳登录页** | 后端已作废旧令牌，用户下一次操作才会莫名 401 |

### 8.2 前端 mock 与真实接口的偏差

`frontend/src/api/mock.js` 只在显式开启时才生效（默认关），但它**已经和真实后端对不上了**，
临时开 mock 调试时会误导：

| 接口 | mock 返回 | 真实后端返回 |
|---|---|---|
| `POST /courseware/upload` | `{ id, name, status: 'UPLOADED' }`，**没有 `pageCount`** | `{ id, name, status: 'CONVERTED', pageCount }` |
| `GET /courseware/{id}/pages` | 页 ID 从 `1000` 开始 | 数据库自增 ID，无规律 |
| `POST /qa/ask` | 返回 `{ id, question, answer, askedAt }`，**没有 `status`** | 多一个 `status` 字段 |

> 真要开 mock 调试，记得先按上表对齐，否则会照着假数据写出错误的取值逻辑。

> ⚠️ **mock 开关**：`frontend/src/api/mock.js` 的 `USE_MOCK` **默认关闭**，
> 即使 `npm run dev` 也走真实后端。想临时看假数据，在 `frontend/.env.local` 里写
> `VITE_USE_MOCK=true`。
>
> 这个开关曾经写成 `import.meta.env.DEV`，导致开发环境下**所有请求都被本地假数据拦截**，
> 上传课件看起来「成功了」但实际一个网络请求都没发。别改回去。

---

## 附录 A：错误码速查

| `code` | 含义 | 用户可见文案示例 |
|---:|---|---|
| 0 | 成功 | `ok` |
| 400 | 业务/参数错误 | `用户名已存在`、`原密码错误`、`只支持 .pptx 格式的课件` |
| 401 | 未认证 / 认证过期 | `未登录或登录已过期`、`用户名或密码错误` |
| 403 | 无权限 | `无权访问` |
| 404 | 资源不存在 | `课件不存在`、`幻灯片不存在`、`接口不存在`（`用户不存在` 实际不可达，见 §3.3） |
| 500 | 服务器内部错误 | `服务器内部错误` |

## 附录 B：本文档的核对来源

| 内容 | 来源文件 |
|---|---|
| 接口路径、方法、参数 | `controller/AuthController.java`、`CoursewareController.java`、`SlideController.java` |
| 请求/响应字段与校验 | `dto/*.java`（共 11 个 record） |
| 响应信封与异常映射 | `common/ApiResponse.java`、`common/GlobalExceptionHandler.java`、`common/BusinessException.java` |
| 权限白名单、CORS、安全头 | `config/SecurityConfig.java` |
| token 校验细节 | `security/JwtService.java`、`security/JwtAuthenticationFilter.java` |
| 业务错误文案 | `service/AuthService.java`、`CoursewareService.java`、`CoursewareUploadService.java`、`FileStorageService.java` |
| 端口、超时、上传限制 | `resources/application.yml` |
| 前端调用约定 | `frontend/src/api/http.js`、`frontend/vite.config.js` |
| **第六节 未实现接口规格** | `docs/api-contract.md`（第 2.5 / 3 / 4 / 5 节）、`docs/ai-agent-design.md`（V2） |
| **§6.1 的 6 个不一致** | `db/schema.sql`、`entity/ClassSession.java`、`entity/QaRecord.java`、`项目看板.md:86`、`frontend/src/pages/LivePage.vue:34` |
| 前端已知待修项 | `frontend/src/pages/*.vue` |

### 改文档的时候，这几处最容易过期

| 改了什么 | 本文档要跟着改哪里 |
|---|---|
| 接口路径 / 方法 | §二 总表 + 对应接口小节标题 |
| DTO 字段 | 对应接口的请求/响应字段表 |
| 错误文案字符串 | 对应接口的「错误」表 + 附录 A |
| `SecurityConfig` 白名单 | §二 各接口的「权限」列 |
| 新增未实现接口 | §6.2 清单 + 补一节规格 |
| 实现完某个接口 | **从 §六 挪到 §一~五**，并同步 §二 的两张表 |

> ⚠️ **最后一行的纪律最重要**：§六 里每实现一个接口，就必须把它移出第六节。
> 否则文档会变成「说了没做」，下次读的人会照着不存在的接口写前端代码——
> `docs/ai-agent-design.md` V1 就犯过这个错（把未实现接口写成已实现，被架构审查记为 Critical）。

---

## 附录 C：修订记录

### C.1 第一轮核对（2026-09-14）：发现并修正 8 处错误

第一~五节写完后，做过一次**对抗式核查**：把文档逐条对着
3 个 Controller、11 个 DTO、`GlobalExceptionHandler` 的 11 个 handler、
`SecurityConfig`、`JwtService`/`JwtAuthenticationFilter`、三个 Service、`application.yml`、
以及 `http.js`/`vite.config.js` 复核了一遍。发现的问题：

| # | 位置 | 错在哪 | 已改成 |
|---:|---|---|---|
| 1 | §4.2 | 写着「页数 > 500 → `课件页数过多（N 页）`」，但**这条文案实际永远显示不出来** | 改成 `课件解析失败…`，并写明这是一个**代码 bug**（`PptxConverter` 的 catch 吞掉了 `BusinessException`） |
| 2 | §4.2 | 「没选文件」漏了两种形态 | 拆成三行（非 multipart / 缺 part / 空文件），各有各的文案 |
| 3 | §六 开头、§2.2 | 写「调用会得到 404」，**未带令牌时其实是 401** | 改成按「带不带令牌」分两种，并解释为什么 |
| 4 | §1.3、附录 A | 把 `用户不存在` 列为会发生，**与本文件 §3.3 自述矛盾** | 删掉，并注明实际不可达 |
| 5 | §3.2 | 「用户名/密码为空」只写了 `用户名不能为空` | 拆成两条，并说明多个错误用 `；` 拼接 |
| 6 | §3.4 | 漏了 `原密码不能为空` / `新密码不能为空` | 补上 |
| 7 | §4.4、§5 | 漏了「参数不是数字」的 400；§5 还漏了模板不匹配时是**真 404** | 补上，§5 改成三种情况对照表 |
| 8 | §七 | 自测期望值写死 `"id":2`，全新库上其实是 1 | 改成 `N` 并加说明 |

### C.2 核对通过、确认无误的部分

| 核对项 | 结论 |
|---|---|
| 接口清单（8 个 `/api` + 1 个 `/slides`） | ✅ 无漏无多 |
| 路径与方法 | ✅ 9 条全部逐字吻合，含 `/slides/{coursewareId}/page{pageNo}.html` 的字面写法 |
| 请求字段与约束 | ✅ `@NotBlank`/`@Size` 的 min/max 全部对上 |
| 响应字段 | ✅ **注册是扁平、登录是嵌套 `user`**，没有搞反；字段顺序与可空性也对 |
| HTTP 状态码 vs 业务 code | ✅ 11 个 `@ExceptionHandler` 逐个核对，无一搞错 |
| 错误文案 | ✅ 标点、空格、字数逐字一致 |
| 权限标签 | ✅ 8 条与白名单 + `@PreAuthorize` 全一致 |
| 分页与边界 | ✅ `size∈[1,100]`、keyword 上限 100、LIKE 转义符 `!` 全对 |
| 令牌细节 | ✅ 7 天、claims `sub/uid/role/ver`、两处查库全对 |
| CORS 与安全头 | ✅ 仅 `/api/**`、`/slides/**` 确实没配 |

### C.3 尚未核对的项（写在这里免得误以为已经查过）

| 项 | 状态 |
|---|---|
| **第六节 7 个接口的规格** | ⚠️ 只与契约/设计文档/表结构对过，**没有代码可验证**（因为还没实现） |
| 与 `docs/api-contract.md` 的逐字 diff | ⚠️ 只做了字段名抽查，未逐行比对 |
| 需要上传真实 .pptx 才能触发的分支 | ⚠️ 未跑（`课件中没有可解析的幻灯片`、`文件内容不是有效的 .pptx`、`文件过大`、页数超限那条 bug） |

### C.4 哪些结论是「真实请求跑出来的」

下面这些**不是读代码推的**，是 2026-09-14 对着**正在运行的后端**打真实请求验证的：

| 验证项 | 实测结果 |
|---|---|
| `POST /api/courseware/upload` 不带 `-F` | `400 上传请求不合法：文件缺失或超过大小限制` |
| 同上，是 multipart 但无 `file` 字段 | `400 缺少上传的文件` |
| `GET /slides/1/page1.html`（文件不存在） | HTTP **200** + `404 幻灯片不存在` |
| `GET /slides/abc/page1.html` | 200 + `400 参数格式不正确：coursewareId` |
| `GET /slides/1/pagex.html` | 200 + `400 参数格式不正确：pageNo` |
| `GET /slides/1/page.html` | 200 + `400 参数格式不正确：pageNo` |
| `GET /slides/1/nope.html` | HTTP **404** + `404 接口不存在` |
| 带合法 token 调未实现接口 | HTTP **404** + `404 接口不存在` |
| 不带 token 调未实现接口 | HTTP **401** + `401 未登录或登录已过期` |

> 核查过程中有一条**推断被实测推翻**，记在这里以免日后重犯：
> 曾据代码推断「`/slides/1/page.html` 模板不匹配 → 走 `NoResourceFoundException` → HTTP 404」。
> **实测是 HTTP 200 + 400**——Spring 的路径模板把 `{pageNo}` 匹配成了**空字符串**，
> 于是类型转换失败，而不是路径不匹配。
> 一个只在读代码、没跑请求的核对者很容易写错这条。
