# 周五课堂（Friday Class）— 智能教学互动平台

> 基于大语言模型的智能教学互动平台，核心闭环：**课前课件解析 → 课中直播问答 → 课后自动总结**。

## 一句话定位

教师上传 `.pptx` 课件，AI 自动逐页抽取知识点并预生成提问；授课时教师录屏直播、翻页自动广播页码，学生端 AI 助手随页码切换加载对应页知识点进行文字问答；课后基于「课件 + 知识点 + 问答记录」自动生成课程总结。

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
├── CLAUDE.md          # 项目宪章（选题 / 决策 / 规范）
├── README.md          # 项目说明
├── db/
│   └── schema.sql     # 数据库建表脚本（9 张表）
├── docs/              # 文档 / 架构设计图
├── backend/           # SpringBoot 后端
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
