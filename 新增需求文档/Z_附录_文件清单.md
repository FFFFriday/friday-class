# Z · 附录 —— 文件清单（按阶段）

> 所属：【周五课堂新增需求】文档集 ｜ 上级索引：`00_总览_需求整理与开发计划.md`
> 用途：开工前对照本清单，能一眼看出**每个阶段要动哪些文件、是新增还是修改**。

代码根目录：`C:\Users\Friday\Desktop\Friday Project\Friday Class(Finish)`

---

## P0 · 地基

### 数据库

| 文件 | 动作 |
|---|---|
| `db/migration/V2__new_features.sql` | **新增**（4 张新表 + 3 张表改列，幂等） |
| `db/seed_admin.sql` | **新增**（管理员账号，密码哈希由后端 BCrypt 生成后填入） |
| `db/schema.sql` | 更新（把新表结构同步进基线） |

### 后端

| 文件 | 动作 | 说明 |
|---|---|---|
| `enums/Role.java` | 修改 | +`ADMIN` |
| `enums/SessionStatus.java` | 修改 | +`PAUSED` |
| `entity/ChatMessage.java` | **新增** | |
| `entity/AiConversation.java` | **新增** | |
| `entity/SessionParticipant.java` | **新增** | |
| `entity/AdminAuditLog.java` | **新增** | |
| `entity/ClassSession.java` | 修改 | +`pausedAt` / `endedBy` / `endReason` |
| `entity/QaRecord.java` | 修改 | +`conversationId` / `coursewareId`；`pageId` 可空 |
| `entity/CourseSummary.java` | 修改 | +`status` / `errorMessage` / `source` |
| `repository/ChatMessageRepository.java` | **新增** | |
| `repository/AiConversationRepository.java` | **新增** | |
| `repository/SessionParticipantRepository.java` | **新增** | |
| `repository/AdminAuditLogRepository.java` | **新增** | |
| `config/SecurityConfig.java` | 修改 | **+`/api/admin/**` → `hasRole("ADMIN")` 一行** |
| `ws/SessionRegistry.java` | 修改 | 改为 `Map<sessionId, Map<userId, Participant>>` |
| `ws/PageWebSocketHandler.java` | 修改 | +消息分支；**翻页仍走 POST** |
| `service/ClassSessionService.java` | 修改 | +暂停/恢复；下课记录 `endedBy` |

### 前端

| 文件 | 动作 | 说明 |
|---|---|---|
| `src/styles/tokens.css` | **新增** | 设计令牌（颜色/间距/圆角/阴影/字号） |
| `src/styles/base.css` | **新增** | reset + 排版 |
| `src/components/base/*` | **新增** | Button / Card / Modal / Input / Table / Tag / Badge / Toast / Skeleton / EmptyState / Loading |
| `src/main.js` | 修改 | 引入全局样式 |

---

## P1 · 实时核心（M1 + M2）

### 后端

| 文件 | 动作 | 说明 |
|---|---|---|
| `controller/ChatController.java` | **新增** | 拉历史、删言 |
| `service/ChatService.java` | **新增** | 落库、限流、`clientMsgId` 去重 |
| `controller/SessionController.java` | 修改 | +`POST/GET /api/session/{id}/stream` |
| `ws/SessionRegistry.java` | 修改 | +定向投递（按 userId） |
| `ws/PageWebSocketHandler.java` | 修改 | +`chat.send` / `presence.*` / `stream.*` 分支 |

### 前端

| 文件 | 动作 | 说明 |
|---|---|---|
| `src/pages/TeachPresentPage.vue` | **新增** | 纯净演示页 `/present/:sessionId` |
| `src/composables/useScreenShare.js` | **新增** | 老师侧：采集 + 逐学生建连 + 轨道管理 |
| `src/composables/useScreenViewer.js` | **新增** | 学生侧：拉流 + 播放控制 |
| `src/composables/useClassSocket.js` | **新增** | 统一封装升级后的 WS |
| `src/composables/useClassChat.js` | **新增** | 讨论区状态与收发 |
| `src/components/features/ClassChatPanel.vue` | **新增** | 讨论区面板 |
| `src/pages/LivePage.vue` | 修改 | +「进入课堂」手势门、`<video>`、讨论区/知识点/AI 三标签页 |
| `src/pages/TeacherLivePage.vue` | 修改 | +共享按钮、在线名单、连接状态 |
| `src/router/index.js` | 修改 | +`/present/:sessionId` |

---

## P2 · 学生 AI（M4 + M3）

### 后端

| 文件 | 动作 | 说明 |
|---|---|---|
| `controller/AiConversationController.java` | **新增** | 会话 CRUD |
| `service/AiConversationService.java` | **新增** | 会话归属校验、CRUD |
| `service/QaService.java` | 修改 | **删除省钱两件套** + 接会话上下文 |
| `llm/PromptTemplates.java` | 修改 | `qaUserPrompt` +`history` 参数 |
| `dto/QaAskRequest.java` | 修改 | +可选 `conversationId` |
| `dto/QaRecordResponse.java` | 修改 | +会话信息、`turn` |
| `application.yml` | 修改 | +`app.ai.context-turns` |

### 前端

| 文件 | 动作 | 说明 |
|---|---|---|
| `src/pages/AiChatPage.vue` | **新增** | 主聊天界面 |
| `src/pages/AiConversationsPage.vue` | **新增** | 会话列表 |
| `src/components/features/ConversationList.vue` | **新增** | 会话侧栏 |
| `src/components/features/MessageBubble.vue` | **新增** | 消息气泡（**纯文本插值**） |
| `src/components/features/PageKnowledgePanel.vue` | **新增** | 当前页知识点 + 预置提问 |
| `src/composables/useConversations.js` | **新增** | 会话 CRUD |
| `src/composables/useChatHistory.js` | **新增** | 消息加载与发送 |
| `src/pages/CoursewareDetailPage.vue` | 修改 | +「知识点」浏览视图、+「问 AI」入口 |
| `src/layouts/AppLayout.vue` | 修改 | +「AI 助手」导航 |
| `src/router/index.js` | 修改 | +`/ai`、`/ai/:id` |

---

## P3 · 记录与总结（M5）

### 后端

| 文件 | 动作 | 说明 |
|---|---|---|
| `controller/SessionRecordController.java` | **新增** | 概览 / 发言 / 分学生问答 / 总结 |
| `service/SessionSummaryService.java` | **新增** | 异步生成、空记录拦截、超长截断 |
| `config/AiExecutorConfig.java` | 修改 | **+`summaryExecutor`（单线程，独立于解析池）** |
| `llm/CallKind.java` | 修改 | +`SUMMARY`（长超时、大 token、低温度） |

### 前端

| 文件 | 动作 | 说明 |
|---|---|---|
| `src/pages/SessionRecordPage.vue` | **新增** | 课堂记录主页 `/record/:sessionId` |
| `src/components/features/ChatTimeline.vue` | **新增** | 发言时间线 |
| `src/components/features/StudentQaList.vue` | **新增** | 分学生 AI 问答（**只显示聊过的**） |
| `src/components/features/SummaryPanel.vue` | **新增** | 总结生成与展示 |
| `src/pages/TeacherLivePage.vue` | 修改 | +「查看课堂记录」入口 |
| `src/router/index.js` | 修改 | +`/record/:sessionId` |

---

## P4 · 管理端（M6）

### 后端

| 文件 | 动作 | 说明 |
|---|---|---|
| `controller/admin/AdminUserController.java` | **新增** | 用户管理 7 接口 |
| `controller/admin/AdminSessionController.java` | **新增** | 课堂管理 10 接口 |
| `controller/admin/AdminCoursewareController.java` | **新增** | 课件与存储 6 接口 |
| `controller/admin/AdminAuditController.java` | **新增** | 审计日志 1 接口 |
| `service/AdminUserService.java` | **新增** | |
| `service/AdminSessionService.java` | **新增** | 强制下课 / 暂停 / 批量 / 踢人 |
| `service/CoursewareDeleteService.java` | **新增** | **DB + 源文件 + 幻灯片 三处一起清** |
| `service/AdminAuditService.java` | **新增** | `record(...)` 统一收口 |
| `service/StorageScanService.java` | **新增** | 占用统计、孤立文件扫描（**只列不删**） |
| `dto/admin/*` | **新增** | 各请求/响应 DTO |

### 前端

| 文件 | 动作 | 说明 |
|---|---|---|
| `src/layouts/AdminLayout.vue` | **新增** | 管理端布局 |
| `src/pages/admin/AdminDashboard.vue` | **新增** | 概览 |
| `src/pages/admin/AdminUsersPage.vue` | **新增** | 用户管理 |
| `src/pages/admin/AdminSessionsPage.vue` | **新增** | 课堂管理（含"暂停所有"） |
| `src/pages/admin/AdminCoursewarePage.vue` | **新增** | 课件与存储 |
| `src/pages/admin/AdminAuditPage.vue` | **新增** | 审计日志 |
| `src/stores/auth.js` | 修改 | +`isAdmin` |
| `src/router/index.js` | 修改 | +`/admin/**` 与 `adminOnly` 守卫 |

---

## P5 · UI 收口（M7）

| 文件 | 动作 | 说明 |
|---|---|---|
| `src/styles/tokens.css` | 已存在 | P0 建，此处微调 |
| 老页面（14 个 `.vue`） | 修改 | 回填设计令牌，去掉硬编码色值 |
| `src/components/base/*` | 已存在 | P0 建，此处补齐缺口 |

---

## 全阶段都该同步的文档

| 文件 | 说明 |
|---|---|
| `API接口文档.md` | 接口的**唯一权威文档**，每加一个接口就更新 |
| `项目看板.md` | 功能×技术×状态总看板 |
| `docs/api-contract.md` | 前后端接口契约 |
| `docs/ai-agent-design.md` | AI 智能体设计（M4 改动后需更新） |

---

## ⚠️ 开跑前的一次性准备

迁移到 Finish 后，以下**被 gitignore 的东西不在仓库里**，需要手动补：

| 项 | 状态 | 命令 / 说明 |
|---|---|---|
| `backend/src/main/resources/application-local.yml` | ✅ **已补** | 迁移时已从 MVP 复制 |
| `backend/storage/`（课件 + 幻灯片） | ✅ **已补** | 迁移时已复制（25 MB） |
| `项目看板.md` 的未提交改动 | ✅ **已补** | 迁移时已复制 |
| `frontend/node_modules/` | ❌ **未补（45 MB）** | 首次运行前执行 `npm install` |
| `backend/target/` | ❌ **未补** | 首次运行自动重新编译 |

---

## 文件数量速览

| 阶段 | 后端新增 | 前端新增 | 修改（前后端合计） |
|---|---|---|---|
| P0 地基 | 8 | 12+ | 9 |
| P1 实时核心 | 2 | 6 | 6 |
| P2 学生 AI | 2 | 7 | 5 |
| P3 记录总结 | 2 | 4 | 4 |
| P4 管理端 | 10+ | 6 | 2 |
| P5 UI 收口 | 0 | 0 | 14+ |

> 实际数量以开工时为准，本表用于**估算规模**（P4 最大，P0 次之）。
