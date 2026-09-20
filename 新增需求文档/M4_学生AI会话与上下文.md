# M4 · 学生 AI 会话与上下文

> 所属：【周五课堂新增需求】文档集 ｜ 上级索引：`00_总览_需求整理与开发计划.md`
> 阶段：**P2** ｜ 规模：中 ｜ 来源需求：**第 8 条**
> 相关分册：`02_技术约定`、`01_数据库建模`（`ai_conversation`、`qa_record`）、`M3`、`M5`

---

## 一、功能描述

- 学生有**自己的多个 AI 会话**，像豆包 / GPT 一样可新建、切换、重命名、删除
- **每个会话有自己独立的上下文**，会话之间**互不引用**
- **重新登录后会话还在**（不是每次都新开一个 AI）
- **下课后依然能用**（不依赖课堂）

### 归属模型（回应 Friday 的澄清）

> Friday 原话：「记录都属于学生，但是需要打上各个课堂及课件的区分」

**完全正确，这也是本模块的建模依据**：

| 维度 | 归属 | 用途 |
|---|---|---|
| **会话** | 归**学生本人**（`ai_conversation.student_id`） | 学生可建多个、可切换、可跨课延续 |
| **每条消息** | 打上 **课堂 ID + 课件 ID + 页码** 三枚标签 | 于是"课堂记录"= 按课堂ID 过滤，"学生档案"= 按学生ID 过滤，**同一批数据两个视角看，不冲突** |

---

## 二、接口设计

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/ai/conversations` | 学生 | 我的会话列表（按最后活跃倒序） |
| POST | `/api/ai/conversations` | 学生 | 新建会话 |
| GET | `/api/ai/conversations/{id}` | 学生 | 会话详情 + 消息列表 |
| PUT | `/api/ai/conversations/{id}` | 学生 | 重命名 |
| DELETE | `/api/ai/conversations/{id}` | 学生 | 软删除 |
| POST | `/api/qa/ask` | 需登录 | **改造**：body 增加**可选** `conversationId` |

### ⚠️ 最小改动原则

**不新建提问接口**，而是在现有 `POST /api/qa/ask` 上加一个**可选**字段：

- 传了 `conversationId` → 写进该会话
- 没传 → 自动落到该学生在该课件下的"默认会话"（找不到就建一个）

> **这样现有前端不改也能继续跑**，新 UI 才传 `conversationId`。
> 这是本项目一贯的"增量改造"思路——不动老路径，只加新参数。

### 请求 / 响应示例

```jsonc
// POST /api/qa/ask 请求体（改造后）
{
  "sessionId": 13,          // 可空（课后提问为空）
  "pageId": 88,             // 可空（课后提问为空）
  "conversationId": 42,     // 新增·可空
  "question": "什么是TCP三次握手",
  "clientRequestId": "uuid-xxxx"
}

// 响应（沿用现有 QaRecordResponse，增加会话信息）
{ "code": 0, "message": "ok",
  "data": {
    "id": 301, "conversationId": 42, "question": "...", "answer": "...",
    "status": "SUCCESS", "turn": 3, "askedAt": "2026-09-20T22:10:00"
  } }

// GET /api/ai/conversations
{ "code": 0, "message": "ok", "data": { "items": [
  { "id": 42, "title": "离散数学答疑", "coursewareId": 24, "sessionId": 13,
    "messageCount": 12, "lastActiveAt": "2026-09-20T22:10:00" } ] } }
```

**错误**：`BIZ_CONVERSATION_NOT_FOUND`（会话不存在或不属于当前用户）、`BIZ_RATE_LIMITED`

---

## 三、技术方案

### 3.1 删除"省钱两件套"（按 Friday 指示）

`service/QaService.java` 中移除两处：

| 移除项 | 原作用 | 移除理由 |
|---|---|---|
| 答案复用缓存（`DEDUP_TTL=10min`、`MAX_CACHE_ENTRIES=2000`） | 10 分钟内相同问题复用答案 | 按指示不省钱；**且会跨会话串台** |
| 并发合流（`inFlight` 单飞） | 并发同问只调一次模型 | 同上 |

**保留**：`clientRequestId` + `uk_qa_client_req` 唯一索引的幂等。
→ 这不省模型钱，是防"手抖双击重复扣费"，无害且必要。

### 3.2 为什么必须删（"上下文打架"到底指什么）

用大白话讲：

> - **答案复用**是**按问题文字**缓存的，它**根本不认识「会话」是什么**。
> - A 会话问「什么是 TCP」→ 得到答案 X（**这个 X 是按 A 的上下文生成的**）
> - 10 分钟内 B 会话问同一句「什么是 TCP」→ **B 拿到 X**
> - → **B 看到的答案是 A 的上下文里的，串台了。**

**Friday 的指示（不要省钱）正好解决了这个问题**，两件事指向同一个方向。

### 3.3 上下文组装

```
取该会话最近 N 轮问答（默认 N=6）→ 按时间正序拼成历史
→ 与「当前页知识点 + 预置提问 + 本次问题」一起组成 prompt
```

| 项 | 决定 |
|---|---|
| **窗口大小** | **6 轮**（可配置 `app.ai.context-turns`）。这是**兜底**，不是优化 —— 防止会话无限增长撑爆模型上下文 |
| **检索方式** | `WHERE conversation_id=? ORDER BY id DESC LIMIT 12`（6 轮 = 12 条问答），再反转为正序 |
| **超长处理** | 单轮问答超长时按字符截断（沿用现有 `PromptTemplates` 的截断策略） |
| **不做** | 摘要压缩、向量检索、跨会话引用（按 Friday 指示不做） |
| **提示词改造** | `PromptTemplates.qaUserPrompt(...)` 增加 `history` 参数；**历史部分同样走 `sanitize()` 包裹**，防注入 |

### 3.4 课后可用

- 提问时 `sessionId` / `pageId` **允许为空**（依赖 `01_数据库建模.md` §3.2 把 `page_id` 改成可空）
- 课后入口：新页面 `/ai`（会话列表）与 `/ai/:conversationId`（聊天），以及课件详情页的「问 AI」按钮

### 3.5 成本与限额（不省钱，但要防失控）

| 项 | 值 |
|---|---|
| 提问频率 | 沿用现有 **1 问 / 5 秒** |
| 单会话消息数上限 | **200 条**（超出提示"本会话太长，建议新建"） |
| `CallKind.QA` 的 `max_tokens` | **保持不变** |

---

## 四、前端改动

| 文件 | 动作 | 说明 |
|---|---|---|
| `pages/AiChatPage.vue` | **新建** | 主聊天界面（左会话列表 + 右消息流） |
| `pages/AiConversationsPage.vue` | **新建** | 会话列表（新建 / 重命名 / 删除） |
| `components/features/ConversationList.vue` | **新建** | 会话侧栏 |
| `components/features/MessageBubble.vue` | **新建** | 消息气泡（**纯文本插值**） |
| `composables/useConversations.js` | **新建** | 会话 CRUD |
| `composables/useChatHistory.js` | **新建** | 消息加载 + 发送 |
| `pages/LivePage.vue` | 更新 | AI 面板接入会话（课堂内提问自动落到该课件的会话） |
| `router/index.js` | 更新 | 新增 `/ai`、`/ai/:id` 路由 |
| `layouts/AppLayout.vue` | 更新 | 导航加「AI 助手」入口 |

---

## 五、开发步骤

| # | 步骤 | 验收 |
|---|---|---|
| M4-1 | 建表 + 实体 + 仓库 | 能建会话、能查到 |
| M4-2 | **删除省钱两件套** | 相同问题在不同会话得到不同答案（**重点回归**） |
| M4-3 | `qa_record` 挂会话 + `page_id` 可空 | 课后提问（无 pageId）能成功落库 |
| M4-4 | 上下文组装 | 第 2 轮提问时 AI 能引用第 1 轮内容 |
| M4-5 | 会话 CRUD 接口 | 建 / 改名 / 删 / 列表 全部可用 |
| M4-6 | 聊天 UI + 会话侧栏 | 切换会话消息流正确切换 |
| M4-7 | 重登恢复 | 退出登录再进来，会话和消息都在 |
| M4-8 | 课后入口 | 不在任何课堂时也能聊 AI |

---

## 六、验收标准

- [ ] 新建会话 → 提问 → 再问"我上一个问题是什么" → AI **能答出**（**证明上下文生效**）
- [ ] 新建第二个会话问同一问题 → **得到独立答案，不串台**
- [ ] 退出登录 → 重新登录 → 会话与消息**完整还在**
- [ ] 不在课堂上时也能提问，且记录里 `sessionId` 为空
- [ ] 双击发送**只产生一条**记录（幂等仍在）
- [ ] 老问答记录（`conversation_id` 为空）照常可查
