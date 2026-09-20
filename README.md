# 周五课堂（Friday Class）— 智能教学互动平台

> 基于大语言模型的智能教学互动平台，核心闭环：**课前课件解析 → 课中直播问答 → 课后自动总结**。

## 一句话定位

教师上传 `.pptx` 课件，AI 自动逐页抽取知识点并预生成提问；授课时教师录屏直播、翻页自动广播页码，学生端 AI 助手随页码切换加载对应页知识点进行文字问答；课后基于「课件 + 知识点 + 问答记录」自动生成课程总结。

## 📖 文档导航（先看这个）

| 我想… | 看这份 |
|---|---|
| **了解项目全貌 / 进度 / 还差什么 / 配置怎么牵扯** | **[`项目看板.md`](项目看板.md)** ⭐ 总入口 |
| **读懂后端代码** | [`backend/README.md`](backend/README.md) 后端阅读指南 |
| 知道接口怎么调 | [`docs/api-contract.md`](docs/api-contract.md) 接口契约 |
| 了解 AI 智能体怎么设计 | [`docs/ai-agent-design.md`](docs/ai-agent-design.md) |
| 看架构图 / 设计图（Word 交付件） | `docs/*.docx` |

## 当前进度（2026-09-11）

| 已做 | 待做 |
|---|---|
| ✅ 数据库 9 张表 | ⬜ F002 AI 课件解析 🛑 缺 DeepSeek Key |
| ✅ 账户与 JWT 认证 | ⬜ F003 直播翻页同步 |
| ✅ 门户课件浏览 | ⬜ F004 学生 AI 问答 |
| ✅ **F001 课件上传与逐页文字抽取** | ⬜ F005/F006 |
| ✅ AI 智能体设计（文档+图） | ⬜ 前端接真实后端（现跑假数据） |

## 快速开始

```bash
# 1) 建库
mysql -u root -p < db/schema.sql
mysql --default-character-set=utf8mb4 -u root -p friday_class < db/seed_teacher.sql  # 预置教师账号

# 2) 后端（先配好 backend/src/main/resources/application-local.yml，见 backend/README.md）
cd backend && mvn spring-boot:run          # → http://localhost:8081

# 3) 前端
cd frontend && npm install && npm run dev  # → http://localhost:5173
```

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

## 技术栈

| 层 | 选型 |
|---|---|
| 大模型 | DeepSeek 纯文本模型（仅文本，无多模态） |
| 前端 | Vue 3 + Vite（JavaScript） |
| 后端 | SpringBoot |
| 数据库 | MySQL 9.5 |
| 实时 | WebSocket（翻页页码广播） |
| 文件存储 | 服务器文件系统（`.pptx` 课件 + 网页幻灯片） |

## 功能模块（F001–F006）

| 编号 | 模块 | 分级 | 说明 |
|---|---|---|---|
| F001 | 课件上传与解析 | A（Must） | `.pptx` → 网页幻灯片 + 逐页文字抽取 |
| F002 | AI 课件解析 | A（Must） | DeepSeek 逐页生成知识点 + 预置提问 |
| F003 | 直播授课与翻页同步 | A（Must） | 录屏直播 + 翻页广播页码 |
| F004 | 学生端 AI 问答助手 | A（Must） | 按当前页加载提示词，文字问答 |
| F005 | 课后总结归纳 | B（Should） | 课件 + 知识点 + 问答 → 自动总结 |
| F006 | 学情统计 | C（Could） | 问答记录聚合统计 |

## 目录结构

```
.
├── CLAUDE.md          # 项目宪章（选题 / 决策 / 硬性规范）
├── README.md          # 项目说明（本文件）
├── 项目看板.md         # ⭐ 项目总看板（进度 / 功能 / 配置 / 未实现）
├── db/
│   ├── schema.sql         # 建表脚本（9 张表）
│   └── seed_teacher.sql   # 教师账号预置脚本
├── docs/              # 文档 / 设计图 / 交付件
│   ├── api-contract.md        # 接口契约（前后端合同）
│   └── ai-agent-design.md     # AI 智能体设计
├── backend/           # SpringBoot 后端
│   └── README.md          # 后端阅读指南
└── frontend/          # Vue 前端
```

## 数据库（9 张表）

`user` · `courseware` · `courseware_page` · `knowledge_point` · `preset_question` · `class_session`（课堂/直播会话） · `qa_record` · `course_summary` · `ai_parse_task`（AI 解析任务）

初始化：

```bash
mysql -u root -p < db/schema.sql
```

## 团队

- 组长：刘康旭
- 组员：张津玮
