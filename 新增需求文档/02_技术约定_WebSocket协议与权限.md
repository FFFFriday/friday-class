# 02 · 技术约定 —— WebSocket 协议与权限

> 所属：【周五课堂新增需求】文档集 ｜ 上级索引：`00_总览_需求整理与开发计划.md`
> 本册是 **M1～M7 全部模块共用的底层约定**，各分册不再重复展开。

---

## 一、WebSocket 消息协议 v2

### 1.1 现状与改造边界

| 项 | 现状 |
|---|---|
| 端点 | 单一 `/ws/page`（裸 `TextWebSocketHandler`，**非 STOMP**） |
| 方向 | **仅下行**（刻意设计） |
| 鉴权 | 连接后 5 秒内发 `{"type":"auth","token":"..."}`；服务端从 JWT 取角色，**不信任 URL 参数** |
| 注册表 | `SessionRegistry`：`Map<Long sessionId, Set<WebSocketSession>>` |
| 广播 | `PageBroadcaster`，须在事务提交后调用 |

> ### ⚠️ 改造时**必须守住**的边界
>
> **翻页继续走 `POST /api/session/{id}/page` 不变，WebSocket 只新增「下行事件」和「上行聊天」。**
>
> 原因：现有 handler **明确拒绝**客户端发来的消息（`PageWebSocketHandler:166`），这是刻意设计——
> 保证老师翻页不被掉线的 socket 卡住。改成双向后若处理不当，**聊天一卡，翻页跟着卡**。
>
> **一句话：聊天丢一条无所谓，翻页不能丢。**

### 1.2 客户端 → 服务端

| type | 载荷 | 状态 | 说明 |
|---|---|---|---|
| `auth` | `{token}` | **已有** | 连接后 5 秒内必须发，否则断开 |
| `ping` | `{}` | **已有** | 心跳 |
| `chat.send` | `{content, clientMsgId}` | **新增** | 发讨论区消息（M2） |
| `webrtc.answer` | `{toUserId, sdp}` | **新增** | 学生回信令（M1） |
| `webrtc.ice` | `{toUserId, candidate}` | **新增** | 双向 ICE 候选（M1） |
| `webrtc.ready` | `{}` | **新增** | 学生表示"可以接流了"（M1） |

### 1.3 服务端 → 客户端

| type | 载荷 | 状态 | 说明 |
|---|---|---|---|
| `auth_ok` | `{sessionId, userId, role, online}` | **已有** | `online` 含义扩展为参与者数 |
| `page` | `{pageNo, serverTime}` | **已有** | 翻页广播 |
| `ended` | `{serverTime}` | **已有** | 下课 |
| `pong` | `{}` | **已有** | 心跳回应 |
| `error` | `{message}` | **已有** | 错误 |
| `chat.new` | `{id, userId, nickname, content, createdAt}` | **新增** | 新发言（M2） |
| `chat.deleted` | `{id}` | **新增** | 发言被撤回（M2） |
| `presence.join` | `{userId, nickname, role, online}` | **新增** | 有人进入（M1/M2/M6） |
| `presence.leave` | `{userId, nickname, online}` | **新增** | 有人离开 |
| `stream.started` | `{teacherId}` | **新增** | 老师开始共享屏幕（M1） |
| `stream.stopped` | `{}` | **新增** | 老师停止共享（M1） |
| `class.paused` | `{by}` | **新增** | 课堂被暂停（M6） |
| `class.resumed` | `{by}` | **新增** | 课堂恢复（M6） |
| `kicked` | `{reason}` | **新增** | 被踢出，前端提示并断开（M6） |
| `webrtc.offer` | `{fromUserId, sdp}` | **新增** | 老师发给指定学生（M1） |
| `webrtc.ice` | `{fromUserId, candidate}` | **新增** | 定向 ICE（M1） |

### 1.4 实现要点

| 要点 | 说明 |
|---|---|
| **注册表升级** | `SessionRegistry` 从 `Map<sessionId, Set<WebSocketSession>>` 升级为 `Map<sessionId, Map<userId, Participant>>`，`Participant` 含 `userId / role / session / joinedAt` |
| **定向投递** | 新增"按 userId 发消息"的能力（信令、踢人需要）。**现有只有广播，这是必须新增的** |
| **不解析内容** | 服务端对 SDP / ICE **只做按 `toUserId` 转发**，不解析、不改写（保持解耦） |
| **清扫线程** | 未认证连接的 1 秒清扫线程**保持不动** |
| **事务后广播** | 沿用现有约定：任何广播必须在事务提交后调用 |

---

## 二、权限模型

### 2.1 三层分工

| 层 | 规则 |
|---|---|
| **后端** | **唯一边界**。`/api/admin/**` → `hasRole('ADMIN')`，在 `SecurityConfig` 显式配置，**deny-by-default** |
| **前端** | 路由守卫**仅 UX**，**不算安全边界**（沿用项目既有约定，后端才是墙） |
| **角色来源** | 新增 `Role.ADMIN`。按项目既有策略：**注册只建学生，教师与管理员由 SQL 预置** |

### 2.2 管理端权限配置（最关键的一处）

现有 `SecurityConfig` 的写法是「白名单 + 少量 `hasRole` 规则」。新增管理端时需要：

```java
// 放在「公开白名单」之后、「默认 deny」之前
requestMatchers("/api/admin/**").hasRole("ADMIN")
```

> ### ⚠️ 为什么把这一行单独拎出来讲
>
> 这是**整个 M6 模块唯一有安全后果的一行配置**。
> **漏配** = 任何登录学生都能调管理接口 = **能删库、能重置任何人密码**。
>
> 因此 **M6 的第一条验收项**就是：用**学生 token** 调 `/api/admin/users`，**必须返回 403**。
> 详见 `M6_管理端.md`。

### 2.3 各模块的鉴权速查

| 模块 | 接口前缀 | 鉴权 |
|---|---|---|
| M1 | `/api/session/{id}/stream` | 写操作**仅教师**；读操作需登录 |
| M2 | `/api/chat/**` | 读需登录；删除**教师/管理员** |
| M3 | `/api/courseware/{id}/prompt-pack` | **需登录**（学生可调，**已有**） |
| M4 | `/api/ai/conversations/**` | **仅学生本人**（校验归属） |
| M5 | `/api/session/{id}/record/**`、`/summary` | **教师/管理员** |
| M6 | `/api/admin/**` | **仅 ADMIN** |

---

## 三、审计动作码约定

供 `admin_audit_log.action` 字段使用（详见 `01_数据库建模.md` §2.4）。

| 分组 | 动作码 | 含义 |
|---|---|---|
| 用户管理 | `USER_CREATE` | 新建账号 |
| | `USER_DISABLE` / `USER_ENABLE` | 禁用 / 启用 |
| | `USER_RESET_PWD` | 重置密码 |
| | `USER_CHANGE_ROLE` | 修改角色 |
| | `USER_DELETE` | 删除账号 |
| 课堂管理 | `SESSION_FORCE_END` | 强制下课 |
| | `SESSION_PAUSE` / `SESSION_RESUME` | 暂停 / 恢复单个课堂 |
| | `SESSION_PAUSE_ALL` / `SESSION_END_ALL` | 批量暂停 / 批量下课 |
| | `SESSION_KICK` | 踢人 |
| 课件管理 | `COURSEWARE_DELETE` | 删除课件 |
| | `COURSEWARE_REPARSE` | 重新解析 |
| 存储 | `STORAGE_ORPHAN_CLEAN` | 清理孤立文件 |

**落地方式**：所有写操作后调用统一入口
`AdminAuditService.record(action, targetType, targetId, detail)`
—— 收口一处，避免各处漏写。

---

## 四、错误码

沿用现有 `ApiResponse{code, message, data}` 信封。本次新增业务码：

| code | 含义 | 出现在 |
|---|---|---|
| `BIZ_RATE_LIMITED` | 触发限流（讨论区发言 / AI 提问） | M2 / M4 |
| `BIZ_CONVERSATION_NOT_FOUND` | 会话不存在或不属于当前用户 | M4 |
| `BIZ_SESSION_NOT_LIVE` | 课堂不在进行中 | M1 / M6 |
| `BIZ_STREAM_NOT_AVAILABLE` | 当前无共享流 | M1 |
| `BIZ_SUMMARY_EMPTY` | 无聊天记录，无法生成总结 | M5 |
| `BIZ_SUMMARY_RUNNING` | 总结正在生成中 | M5 |

**约定**：HTTP 状态码与业务 `code` 分离 —— 沿用现有做法，业务失败一般仍返回 HTTP 200，由 `code` 表达；401/403 例外（走 HTTP 状态码）。

---

## 五、其他全局约定

| 约定 | 内容 |
|---|---|
| **XSS 防护** | 一切用户输入内容**纯文本存储**、**纯文本插值渲染**（`{{ }}`），**严禁 `v-html`** —— 项目既有铁律 |
| **限流写法** | 沿用现有 `QaService` 的**进程内**限流（无 Redis）。注意：重启清零、多实例各算一份 |
| **异步任务** | 项目**没有 `@Async`**，用一个显式 `ThreadPoolTaskExecutor` bean。新增后台任务**不要复用解析池**（详见 M5） |
| **CORS** | 前端固定 `http://localhost:5173`，**本次不动** |
| **端口** | 后端 8081，前端 5173 |
