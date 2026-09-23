# 周五课堂（Friday Class）— 智能教学互动平台

> 基于大语言模型的智能教学互动平台，核心闭环：**课前课件解析 → 课中直播问答 → 课后自动总结**。

## 一句话定位

教师上传 `.pptx` 课件，AI 自动逐页抽取知识点并预生成提问；授课时教师共享屏幕与声音、翻页自动广播页码，学生端 AI 助手随页码切换加载对应页知识点进行文字问答；课后基于「课件 + 知识点 + 问答记录」自动生成课程总结。

---

## 🤖 人工智能端的代码在哪里？

**先回答一个最常被问到的问题：本项目的 AI 能力没有独立工程、没有独立进程，它就是后端里的一组包。**

所以你在 `backend/` 下**找不到**叫 `ai` 或 `人工智能` 的目录——这是正常的。相关代码分布在下面这些位置：

| 位置 | 内容 |
|---|---|
| `backend/src/main/java/com/fridayclass/llm/` | **大模型接入层（6 个类）**。`DeepSeekClient` 是 HTTP 客户端（含原生工具调用），`PromptTemplates` 集中存放全部提示词，另有 `CallKind` / `LlmResult` / `LlmToolCall` / `LlmException` |
| `backend/src/main/java/com/fridayclass/service/agent/` | **AI 智能体（24 个类）**。`AgentLoop` 是多步推理主循环（`调模型 → 执行工具 → 结果回填 → 再调模型`，循环上限由 `app.ai.agent-max-steps` 控制，默认 **6**），另有 `AgentTaskService`、`AgentToolRegistry`、`WorkspacePathSandbox`、`WorkspaceFileService`；子包 `tool/` 是 **4 个工具**（`get_courseware_content` 读课件页、`get_class_records` 取课堂记录、`files` 读写工作区文件、`write_file` 产出文件），`export/` 是 **4 个导出器**（Word / Excel / Markdown 直通 / 纯文本） |
| `backend/src/main/java/com/fridayclass/dto/agent/` | 智能体的请求与响应模型（4 个类） |
| `backend/src/main/java/com/fridayclass/service/` （直接放在这里） | 6 个 AI 相关服务：`AiParseService`（课件解析）、`QaService`（学生问答）、`AiConversationService`（会话管理）、`ChatService`（讨论区）、`PromptPackService`（按页下发提示词包）、`SessionSummaryService`（课后总结） |
| `backend/src/main/java/com/fridayclass/entity/` · `repository/` | 7 张 AI 相关表的实体与仓储：`ai_parse_task`、`ai_conversation`、`qa_record`、`knowledge_point`、`preset_question`、`ai_generated_file`、`ai_agent_run` |
| `db/migration/V4__ai_agent.sql` | AI 智能体两张表的迁移脚本 |
| `docs/ai-agent-design.md` · `docs/周五课堂_AI智能体设计.docx` | AI 智能体的设计说明 |

**为什么这么放**：AI 不是外挂，它要读课件页、要落库问答记录、要鉴权。做成独立服务反而要重造一遍这些通道。
交付包里的「人工智能端源码」是**按上面这些路径抽取出来的子集**（保持原路径），不是另一套工程。

---

## 📖 文档导航（先看这个）

| 我想… | 看这份 |
|---|---|
| **了解项目全貌 / 进度 / 还差什么 / 配置怎么牵扯** | **[`项目看板.md`](项目看板.md)** ⭐ 总入口 |
| **查接口怎么调** | **[`API接口文档.md`](API接口文档.md)** 接口权威文档（含「只有真调模型才知道的 7 件事」） |
| **读懂后端代码** | [`backend/README.md`](backend/README.md) 后端阅读指南 |
| 了解 AI 智能体怎么设计 | [`docs/ai-agent-design.md`](docs/ai-agent-design.md) |
| 看前后端接口契约 | [`docs/api-contract.md`](docs/api-contract.md) |
| 看架构图 / 设计图（Word 交付件） | `docs/周五课堂_架构设计图.docx`、`docs/周五课堂_AI智能体设计.docx` |

> ⚠️ `backend/README.md` 与 `项目看板.md` 的部分章节仍停留在早期版本，**以本文件与 `API接口文档.md` 为准**。

---

## 当前进度（2026-09-23 结项）

**全部功能已完成并验证，`main` 即最新可交付状态（`b9c00bc`）。**

| 项 | 状态 |
|---|---|
| 功能模块 | 12 个定义，**完成 11 个**（F006 学情统计为 C 级增强功能，本期未实施，数据源已具备） |
| 后端 | 186 个 Java 类，**91 个 REST 接口** + 1 个 WebSocket 端点（21 种消息） |
| 前端 | 22 个页面组件 + 23 个公共组件 |
| 数据库 | **19 张表**，迁移脚本 V2 / V3 / V4 均已执行 |
| 代码规模 | 38380 行 / 267 个源文件（含空行与注释） |
| 测试 | 单元测试 71 项全部通过；接口与流程自动化验证 153 项全部通过 |
| 工作量 | 48 人天 |

---

## 快速开始

```bash
# 1) 建库建表（schema.sql 已含全部 19 张表）
mysql -u root -p friday_class < db/schema.sql
mysql --default-character-set=utf8mb4 -u root -p friday_class < db/seed_admin.sql    # 预置管理员
mysql --default-character-set=utf8mb4 -u root -p friday_class < db/seed_teacher.sql  # 预置教师

# 2) 后端（先配好 backend/src/main/resources/application-local.yml，见 backend/README.md）
cd backend && mvn spring-boot:run          # → http://localhost:8081

# 3) 前端
cd frontend && npm install && npm run dev  # → http://localhost:5173
```

**预置账号**：管理员 `admin` / `admin123456`；教师 `teacher` / `teacher123`。
**学生账号不预置**，在登录页自行注册（注册接口只创建学生）。

> `application-local.yml` 不入库（已 gitignore），需自行创建，内含三项必填：
> 数据库密码、JWT 密钥（长度 ≥ 32 字节）、DeepSeek API Key。缺任一项后端启动即失败。

> ### ⚠️ 模型调用报「Connect timed out」——这是本机网络问题，不是代码缺陷
>
> **症状**：AI 回答变成「AI 助教暂时忙不过来，请稍后再试」；日志里是
> `Connect timed out` 连 `api.deepseek.com/chat/completions`；
> **而同一时刻 `curl` 打同一个地址只要 0.2 秒**。时好时坏，极像网络抖动。
>
> **原因**（2026-09-21 实测确认）：`api.deepseek.com` 有**两条 A 记录**，
>
> | 地址 | 从本机 |
> |---|---|
> | `124.225.27.128` | ❌ **不可达**（TCP 连接直接超时） |
> | `171.105.220.186` | ✅ 正常（约 66ms） |
>
> 而 **DNS 返回的顺序会轮换**。坏地址排前面时每次调用都失败，排后面时一切正常——
> 这就是「时好时坏」的来源。
>
> **两个内置 HTTP 客户端都不做地址回落**（都实测过）：`HttpURLConnection`
> 只用第一个地址（超时放宽到 20 秒仍失败）；JDK 的 `java.net.http.HttpClient`
> 同样失败。只有 `curl` 会 Happy Eyeballs 自动回落，所以它一直正常。
>
> **识别口诀：`curl` 能通、Java 程序不能通 → 查解析地址与回落。**
>
> **这是本机到 DeepSeek 某台服务器的路由问题，改代码解决不了。** 缓解办法：
>
> ```bash
> # ① 先确认现在解析到哪几个地址
> nslookup api.deepseek.com
>
> # ② 逐个探通不通（把 IP 换成上面查到的）
> curl -s -o /dev/null -w "%{http_code} %{time_connect}s\n" --max-time 8 --resolve api.deepseek.com:443:124.225.27.128 https://api.deepseek.com
> curl -s -o /dev/null -w "%{http_code} %{time_connect}s\n" --max-time 8 --resolve api.deepseek.com:443:171.105.220.186 https://api.deepseek.com
>
> # ③ 若确认只有一个通，把它固定进 hosts 文件（需要管理员权限）：
> #    C:\Windows\System32\drivers\etc\hosts 追加一行：
> #    171.105.220.186  api.deepseek.com
> #    ⚠️ 这会让所有程序都走这个 IP。DeepSeek 换 IP 时要记得删掉。
> ```
>
> **演示前的自检**：跑一句 curl，0.2 秒内返回就说明现在是通的。
>
> ⚠️ 排查时**别再试这两个被证伪的方向**：
> 「JVM 优先走 IPv6」（`-Djava.net.preferIPv4Stack=true` 实测无效）、
> 「换 JDK HttpClient 就好了」（实测无效）。

---

## 技术栈

| 层 | 选型 |
|---|---|
| 大模型 | DeepSeek 纯文本模型 `deepseek-flash`（仅文本，无多模态 / 无 TTS / 无 ASR） |
| 前端 | Vue 3 + Vite 6 + Vue Router 4 + Pinia 2 + Axios（JavaScript） |
| 后端 | Spring Boot 3.5 + Spring Data JPA + Spring Security + JWT |
| 数据库 | MySQL 9.5（utf8mb4） |
| 实时 | WebSocket（翻页 / 发言 / 在线名单 / 共享信令，共 21 种消息）+ WebRTC（屏幕与音频点对点） |
| 文件存储 | 服务器文件系统（`.pptx` 课件 + 网页幻灯片 + 智能体工作区） |

---

## 功能模块（F001–F012）

| 编号 | 模块 | 分级 | 状态 |
|---|---|---|---|
| F001 | 课件上传与解析 | A（Must） | ✅ |
| F002 | AI 课件解析 | A（Must） | ✅ |
| F003 | 直播授课与实时课堂传输 | A（Must） | ✅ |
| F004 | 学生端 AI 问答助手 | A（Must） | ✅ |
| F005 | 课后总结归纳 | B（Should） | ✅ |
| F006 | 学情统计 | C（Could） | ⬜ **本期未实施** |
| F007 | 课堂讨论区 | A（Must） | ✅ |
| F008 | 课堂记录与回顾 | B（Should） | ✅ |
| F009 | 学生端知识可见 | A（Must） | ✅ |
| F010 | 班级体系与课堂可见性 | B（Should） | ✅ |
| F011 | 管理端 | B（Should） | ✅ |
| F012 | AI 智能体（教师助教） | B（Should） | ✅ |

---

## 目录结构

```
.
├── CLAUDE.md          # 项目宪章（选题 / 决策 / 硬性规范）
├── README.md          # 项目说明（本文件）
├── 项目看板.md         # 项目总看板（进度 / 功能 / 配置 / 技术债）
├── API接口文档.md      # ⭐ 接口权威文档（91 个 REST + 1 个 WS 端点）
├── db/
│   ├── schema.sql         # 建表脚本（19 张表，含 V2~V4 的全部结构）
│   ├── migration/         # 增量迁移：V2__new_features / V3__class_group / V4__ai_agent
│   ├── seed_admin.sql     # 管理员账号预置
│   └── seed_teacher.sql   # 教师账号预置
├── docs/              # 设计与交付文档
│   ├── api-contract.md        # 前后端接口契约
│   ├── ai-agent-design.md     # AI 智能体设计
│   ├── *.puml / *.png         # 架构图、时序图的源文件与图
│   ├── gen_diagram.py 等      # 画图与文档生成的本地脚本
│   └── *.docx                 # Word 交付件（架构设计图 / AI 智能体设计）
├── backend/           # Spring Boot 后端
│   ├── README.md          # 后端阅读指南
│   └── storage/           # 运行数据：courseware / slides / ai-workspace（gitignore）
└── frontend/          # Vue 前端
    └── src/
        ├── pages/         # 22 个页面组件（含 admin/ 与 home/ 子目录）
        ├── components/    # 23 个公共组件
        ├── composables/   # 组合式函数（含 AI 解析、提示词包等）
        ├── stores/        # Pinia 状态管理（auth 是登录态唯一真源）
        ├── api/           # 接口封装
        ├── layouts/       # 布局
        ├── router/        # 路由（按角色分流）
        └── styles/        # 设计令牌 tokens.css + 全局样式 base.css
```

---

## 数据库（19 张表）

**身份与内容**：`user` · `courseware` · `courseware_page` · `knowledge_point` · `preset_question` · `ai_parse_task`

**课堂与互动**：`class_session` · `chat_message` · `session_participant` · `course_summary`

**问答与会话**：`qa_record` · `ai_conversation`

**班级与授权**：`class_group` · `class_group_member` · `session_class_group` · `session_audience`

**运营与智能体**：`admin_audit_log` · `ai_generated_file` · `ai_agent_run`

初始化：

```bash
mysql -u root -p friday_class < db/schema.sql
```

---

## 团队

- 组长：刘康旭（202324120310）
- 组员：张津玮（202324120336）
