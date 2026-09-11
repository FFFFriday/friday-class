# 周五课堂 API 接口契约（前端倒推）

> 本文档由前端页面倒推生成，是后端开发的**任务书**。后端 task6–8 按此实现，前端按此对接。

## 0. 通用约定

| 项 | 约定 |
|---|---|
| Base URL | `/api` |
| 统一响应 | `{ "code": 0, "message": "ok", "data": ... }`，`code=0` 成功，非 0 失败 |
| 认证 | JWT，请求头 `Authorization: Bearer <token>` |
| 分页 | 参数 `page`（从 1 起）、`size`；返回 `data` 内含 `list`/`total`/`page`/`size` |
| 时间 | ISO 8601 字符串（如 `2026-09-10T10:00:00`） |
| 角色 | `role` = `TEACHER`（教师）/ `STUDENT`（学生） |

## 1. 认证模块

### 1.1 注册
- **POST** `/api/auth/register`
- 请求体：
```json
{ "username": "zhangsan", "password": "123456", "nickname": "张三" }
```
- **说明**：注册**不接收 role**，一律创建**学生**账号。教师账号由管理员预置
  （见 `db/seed_teacher.sql`），避免任意人自助注册即可获得教师权限。
- 响应 `data`：
```json
{ "id": 1, "username": "zhangsan", "role": "STUDENT", "nickname": "张三", "token": "xxx" }
```

### 1.2 登录
- **POST** `/api/auth/login`
- 请求体：
```json
{ "username": "zhangsan", "password": "123456" }
```
- 响应 `data`：
```json
{ "token": "xxx", "user": { "id": 1, "username": "zhangsan", "role": "STUDENT", "nickname": "张三" } }
```

### 1.3 当前用户
- **GET** `/api/auth/me`（需登录）
- 响应 `data`：
```json
{ "id": 1, "username": "zhangsan", "role": "STUDENT", "nickname": "张三" }
```

### 1.4 改密码
- **PUT** `/api/auth/password`（需登录）
- 请求体：
```json
{ "oldPassword": "123456", "newPassword": "654321" }
```
- 响应 `data`：`null`

## 2. 课件模块

### 2.1 课件列表（门户首页）
- **GET** `/api/courseware`
- 查询参数：`page`、`size`、`keyword`（名称模糊）、`status`（状态筛选，可选）
- 响应 `data`：
```json
{
  "list": [
    { "id": 1, "name": "数据结构第一章.pptx", "status": "PARSED", "pageCount": 20, "uploaderId": 2, "uploaderName": "刘老师", "uploadedAt": "2026-09-10T10:00:00" }
  ],
  "total": 1, "page": 1, "size": 10
}
```

### 2.2 上传课件（教师）
- **POST** `/api/courseware/upload`（`multipart/form-data`）
- **权限**：需登录**且角色为 TEACHER**；学生调用返回 403。
- 表单字段：`file`（.pptx 文件，≤ 50MB）
- **服务端处理**：落盘 → 用 Apache POI 逐页抽取文字 → 生成每页网页幻灯片
  → 写 `courseware` 与 `courseware_page`。
- 响应 `data`：
```json
{ "id": 1, "name": "数据结构第一章.pptx", "status": "CONVERTED", "pageCount": 20 }
```
- 说明：处理完成后状态为 `CONVERTED`（页面已就绪，**尚未做 AI 知识点解析**）；
  AI 解析属于 F002，会继续流转到 `PARSING → PARSED`。

### 2.3 课件详情
- **GET** `/api/courseware/{id}`
- 响应 `data`：
```json
{ "id": 1, "name": "数据结构第一章.pptx", "status": "PARSED", "pageCount": 20, "uploaderId": 2, "uploaderName": "刘老师", "uploadedAt": "2026-09-10T10:00:00" }
```

### 2.4 课件页列表（幻灯片预览）
- **GET** `/api/courseware/{id}/pages`
- 响应 `data`：
```json
{
  "list": [
    { "id": 101, "pageNo": 1, "textContent": "第一章 绪论……", "slideUrl": "/slides/1/page1.html" }
  ]
}
```

### 2.5 提示词包（学生端进课堂时预下发）
- **GET** `/api/courseware/{id}/prompt-pack`
- **说明**：一次性返回该课件**全部页**的知识点与预置提问，供学生端按 `pageId` 建索引缓存。
  有了它，翻页与提问都不必再查库。**注意 2.4 的页列表不含知识点**，两者是不同用途的接口。
- 响应 `data`：
```json
{
  "coursewareId": 1,
  "parseVersion": 3,
  "pages": [
    {
      "pageId": 101,
      "pageNo": 1,
      "knowledgePoints": ["数据结构的基本概念", "逻辑结构与存储结构的区别"],
      "presetQuestions": ["为什么说数据结构决定了算法效率？"]
    }
  ]
}
```
- `parseVersion`：解析版本号。学生端缓存时一并存下；若后续响应里的值变大，
  说明课件被重新解析过，客户端应**丢弃旧缓存重新拉取**。

## 3. 课堂会话模块（直播）

### 3.1 创建课堂（教师）
- **POST** `/api/session`（需教师登录）
- 请求体：
```json
{ "coursewareId": 1, "title": "数据结构 · 第3周" }
```
- 响应 `data`：
```json
{ "id": 1, "coursewareId": 1, "title": "数据结构 · 第3周", "status": "NOT_STARTED", "streamPushUrl": "rtmp://...", "streamPullUrl": "http://..." }
```

### 3.2 课堂详情（学生加入）
- **GET** `/api/session/{id}`
- 响应 `data`：
```json
{ "id": 1, "title": "数据结构 · 第3周", "status": "LIVE", "currentPage": 3, "streamPullUrl": "http://...", "coursewareId": 1 }
```

## 4. 问答模块（AI 助手）

### 4.1 学生提问
- **POST** `/api/qa/ask`（需学生登录）
- 请求体：
```json
{ "sessionId": 1, "pageId": 103, "question": "这个知识点能再解释一下吗？", "clientRequestId": "b7f2c1a0-..." }
```
- **字段约束**：`question` 必填，**1~500 字**，超长返回 `code=400`；
  `clientRequestId` 为客户端生成的唯一串（幂等键），重复提交同一 ID 不会重复扣费/重复入库。
- 响应 `data`：
```json
{
  "id": 1,
  "question": "这个知识点能再解释一下吗？",
  "answer": "好的，……（AI 回答）",
  "status": "SUCCESS",
  "askedAt": "2026-09-10T10:30:00"
}
```
- `status`：`SUCCESS` 已作答 / `FAILED` 模型调用失败（`answer` 为友好提示文案）。
  学情统计（F006）应以 `status=SUCCESS` 为准，避免把失败请求计入提问量。

### 4.2 问答记录
- **GET** `/api/qa/records?sessionId=1`
- 响应 `data`：
```json
{
  "list": [
    { "id": 1, "question": "……", "answer": "……", "askedAt": "2026-09-10T10:30:00" }
  ]
}
```

## 5. 实时通信

### 5.1 翻页页码广播
- **WS** `/ws/page?sessionId=1&role=TEACHER|STUDENT`
- 老师端翻页时发送：`{ "type": "page", "pageNo": 3 }`
- 服务端**先落库** `class_session.current_page`，再广播给该 session 的所有学生端：
  `{ "type": "page", "pageNo": 3 }`
- **为什么要落库**：迟到进入或断线重连的学生错过了广播，靠 `GET /api/session/{id}`
  返回的 `currentPage` 恢复上下文，否则拿不到页码、AI 上下文就会对不上老师正在讲的内容。
- 学生端收到后随页码切换本地 AI 提示词（提示词预先下发，存浏览器缓存）。
