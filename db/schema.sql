-- =============================================================
-- 周五课堂（Friday Class）— 数据库建表脚本
-- MySQL 9.5（语法兼容 8.0）· InnoDB · utf8mb4
--
-- 说明：本脚本在 SRS 数据字典（7 实体）基础上审查后扩展为 13 张表：
--   · 新增「课堂/直播会话 class_session」——F003 直播、翻页同步、F006 学情统计、
--     F005 课后总结都需挂在「一次上课」上；
--   · 新增「AI 解析任务 ai_parse_task」——F002 是异步逐页调用 DeepSeek，需跟踪进度/失败重试；
--   · 改造 qa_record / course_summary 补充课堂关联；
--   · 全表补 created_at/updated_at 时间戳、deleted 软删除、status 状态字段。
--
-- 【V2 新增需求，2026-09】表 10~13 + 三张老表加列：
--   · chat_message       课堂讨论区发言
--   · ai_conversation    学生 AI 会话（会话管理与上下文记忆）
--   · session_participant 课堂参与者（在线名单 / 出席 / 踢人）
--   · admin_audit_log    管理操作审计日志
--
-- 【V3，2026-09】班级体系 —— 4 张表 + class_session 加 visibility 列：
--   · class_group / class_group_member / session_class_group / session_audience
--
-- 【V4，2026-09】AI 智能体 —— 2 张表：
--   · ai_generated_file  AI 产出物索引（也是下载接口做 owner 校验的唯一依据）
--   · ai_agent_run       任务执行记录（轮数、状态、token 用量 —— 让成本可见）
--
-- ⚠ 已有库**不要重跑本文件**（老表是 CREATE TABLE 不带 IF NOT EXISTS，会报错），
--   升级请按顺序执行 db/migration/ 下的 V2 / V3 / V4（都是幂等的）。
--   本文件只保证「从零建库」拿到完整结构。
--
-- ⚠⚠ 本文件曾经落后于 migration 两个版本（一度只有 V1+V2 的 13 张表，
--   而 README 教人用它建库）—— 后果不是「少个功能」，是**后端起不来**：
--   AgentRunCleaner 是 ApplicationRunner，启动时要查 ai_agent_run，
--   表不存在就抛异常、Spring Boot 直接中止。**再改表结构时请同步本文件。**
-- =============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `friday_class`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_0900_ai_ci;
USE `friday_class`;

-- 1. 用户表
CREATE TABLE `user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户唯一标识，主键',
  `username`      VARCHAR(50)     NOT NULL                COMMENT '登录名，唯一',
  `password_hash` VARCHAR(255)    NOT NULL                COMMENT '密码哈希（bcrypt）',
  `role`          VARCHAR(20)     NOT NULL                COMMENT '角色：TEACHER 教师 / STUDENT 学生（可扩展）',
  `nickname`      VARCHAR(50)     NULL                    COMMENT '昵称',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`       TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '软删除标记：0 正常 / 1 已删除',
  -- 「禁用」与「软删除」是两个维度，不能混用：
  --   禁用 = 临时禁止登录，账号还在、数据都在，随时可启用；
  --   软删除 = 从系统里移除，列表里不再出现。
  -- 用 deleted 兼职禁用的话，一次「启用」就会把删除也一起撤销。
  `disabled`      TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '禁用标记：1=禁止登录',
  `token_version` INT             NOT NULL DEFAULT 0      COMMENT '令牌版本：改密码时自增，使此前签发的 JWT 立即失效',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_username` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

-- 2. 课件表
CREATE TABLE `courseware` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '课件唯一标识，主键',
  `name`        VARCHAR(200)    NOT NULL                COMMENT '课件名称',
  `file_path`   VARCHAR(500)    NOT NULL                COMMENT '.pptx 文件存储路径',
  `uploader_id` BIGINT UNSIGNED NOT NULL                COMMENT '上传者（教师）用户ID，外键',
  `status`      VARCHAR(20)     NOT NULL DEFAULT 'UPLOADED' COMMENT '处理状态：UPLOADED/CONVERTING/CONVERTED/PARSING/PARSED/FAILED',
  `page_count`  INT             NULL                    COMMENT '总页数（转换后填充）',
  `uploaded_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '软删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_courseware_uploader` (`uploader_id`),
  KEY `idx_courseware_status` (`status`),
  CONSTRAINT `fk_courseware_uploader` FOREIGN KEY (`uploader_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课件';

-- 3. 课件页表
CREATE TABLE `courseware_page` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '课件页唯一标识，主键',
  `courseware_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属课件ID，外键',
  `page_no`       INT             NOT NULL                COMMENT '页码，从 1 开始',
  `text_content`  TEXT            NULL                    COMMENT '该页抽取的文字内容',
  `slide_url`     VARCHAR(500)    NULL                    COMMENT '该页网页幻灯片访问地址',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_page_courseware_no` (`courseware_id`, `page_no`),
  KEY `idx_page_courseware` (`courseware_id`),
  CONSTRAINT `fk_page_courseware` FOREIGN KEY (`courseware_id`) REFERENCES `courseware` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课件页';

-- 4. 知识点表
CREATE TABLE `knowledge_point` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '知识点唯一标识，主键',
  `page_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属课件页ID，外键',
  `content`    TEXT            NOT NULL                COMMENT '知识点内容',
  `sort_order` INT             NOT NULL DEFAULT 0      COMMENT '同页内排序（每页 2~5 个）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_kp_page` (`page_id`),
  CONSTRAINT `fk_kp_page` FOREIGN KEY (`page_id`) REFERENCES `courseware_page` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识点';

-- 5. 预置提问表
CREATE TABLE `preset_question` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '预置提问唯一标识，主键',
  `page_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属课件页ID，外键',
  `content`    TEXT            NOT NULL                COMMENT '预置提问内容',
  `sort_order` INT             NOT NULL DEFAULT 0      COMMENT '同页内排序（每页 2~5 个）',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_pq_page` (`page_id`),
  CONSTRAINT `fk_pq_page` FOREIGN KEY (`page_id`) REFERENCES `courseware_page` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预置提问';

-- 6. 课堂/直播会话表（新增）
CREATE TABLE `class_session` (
  `id`               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '课堂唯一标识，主键',
  `courseware_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属课件ID，外键',
  `teacher_id`       BIGINT UNSIGNED NOT NULL                COMMENT '授课教师用户ID，外键',
  `title`            VARCHAR(200)    NULL                    COMMENT '课堂名称（默认用课件名）',
  `status`           VARCHAR(20)     NOT NULL DEFAULT 'NOT_STARTED' COMMENT '直播状态：NOT_STARTED/LIVE/PAUSED/ENDED',
  `stream_push_url`  VARCHAR(500)    NULL                    COMMENT '推流地址（直播方案待定，预留）',
  `stream_pull_url`  VARCHAR(500)    NULL                    COMMENT '拉流地址（直播方案待定，预留）',
  `current_page`     INT             NULL                    COMMENT '当前页码（随翻页广播更新；迟到/重连的学生靠它恢复上下文）',
  `started_at`       DATETIME        NULL                    COMMENT '开始时间',
  `ended_at`         DATETIME        NULL                    COMMENT '结束时间',
  `paused_at`        DATETIME        NULL                    COMMENT '暂停时间，NULL=未暂停',
  `ended_by`         BIGINT UNSIGNED NULL                    COMMENT '结束人，NULL=老师自己下课（非空=管理员强制下课）',
  `end_reason`       VARCHAR(100)    NULL                    COMMENT '结束原因，如 ADMIN_FORCE',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '软删除标记',
  -- 【V3 加列】默认 RESTRICTED 是**拒绝优先**：忘了设就是「谁也看不到」这个显眼的 bug，
  -- 而不是「所有人都能看到」这个沉默的泄漏。与项目既有的安全立场一致。
  `visibility`       VARCHAR(20)     NOT NULL DEFAULT 'RESTRICTED' COMMENT 'PUBLIC=所有人可见 / RESTRICTED=仅 session_audience 内可见',
  PRIMARY KEY (`id`),
  KEY `idx_session_courseware` (`courseware_id`),
  KEY `idx_session_teacher` (`teacher_id`),
  KEY `idx_session_status` (`status`),
  CONSTRAINT `fk_session_courseware` FOREIGN KEY (`courseware_id`) REFERENCES `courseware` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_session_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课堂/直播会话';

-- 7. 问答记录表（补充课堂关联）
CREATE TABLE `qa_record` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '问答记录唯一标识，主键',
  -- ⚠ session_id 与 page_id **都必须可空**：学生课后问 AI 时既不在课堂上、也不在任何页上。
  -- 只放开 page_id 会让课后提问在落库这一步就失败（Column 'session_id' cannot be null）。
  `session_id` BIGINT UNSIGNED NULL                    COMMENT '所属课堂ID，外键；NULL=课后提问（不挂在任何课堂下）',
  `student_id` BIGINT UNSIGNED NOT NULL                COMMENT '提问学生用户ID，外键',
  -- ⚠ 必须可空：学生**下课后**问 AI 时不在任何一页上，没有 page_id 可填。
  -- 保持 NOT NULL 会让课后问答直接落库失败，而「课后也能用 AI」是明确需求。
  `page_id`    BIGINT UNSIGNED NULL                    COMMENT '提问时所在页ID，外键；NULL=课后提问（不在任何页上）',
  `question`   TEXT            NOT NULL                COMMENT '学生提问文本（≤500 字）',
  `answer`     TEXT            NULL                    COMMENT 'AI 回答文本',
  `status`     VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS' COMMENT '问答状态：SUCCESS 已作答 / FAILED 调用失败（便于学情统计区分）',
  `client_request_id` VARCHAR(64) NULL                  COMMENT '幂等键，客户端生成的 UUID；同一 ID 重复提交直接返回上次结果',
  -- 刻意**不加外键**：courseware 可被管理员硬删（管理端功能），
  -- 这里加 FK RESTRICT 会让「删课件」被约束挡住。
  `conversation_id` BIGINT UNSIGNED NULL                COMMENT '所属AI会话；老数据为 NULL 属正常（按「历史对话」展示）',
  `courseware_id`   BIGINT UNSIGNED NULL                COMMENT '所属课件（直连，避免从 page_id 两跳 join）',
  `asked_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  -- 幂等靠它兜底：并发重复提交时只有一个 INSERT 能成功，另一个撞唯一键。
  -- MySQL 唯一索引不约束 NULL，所以不带幂等键的历史数据（多条 NULL）不受影响。
  UNIQUE KEY `uk_qa_client_req` (`client_request_id`),
  KEY `idx_qa_session` (`session_id`),
  KEY `idx_qa_student` (`student_id`),
  KEY `idx_qa_page` (`page_id`),
  KEY `idx_qa_conversation` (`conversation_id`, `id`),
  KEY `idx_qa_session_student` (`session_id`, `student_id`),
  CONSTRAINT `fk_qa_session` FOREIGN KEY (`session_id`) REFERENCES `class_session` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_qa_student` FOREIGN KEY (`student_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_qa_page` FOREIGN KEY (`page_id`) REFERENCES `courseware_page` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答记录';

-- 8. 课程总结表（补充课堂关联）
CREATE TABLE `course_summary` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '课程总结唯一标识，主键',
  `session_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属课堂ID，外键',
  `courseware_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属课件ID，外键（冗余，便于按课件查询）',
  `content`       TEXT            NULL                    COMMENT '总结正文',
  `status`        VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS' COMMENT 'PENDING/RUNNING/SUCCESS/FAILED（异步生成用）',
  `error_message` TEXT            NULL                    COMMENT '失败原因',
  `source`        VARCHAR(20)     NOT NULL DEFAULT 'SESSION_CHAT' COMMENT '总结来源：本次只做 SESSION_CHAT（仅基于聊天记录）',
  `generated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '总结生成时间',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_summary_session` (`session_id`),
  KEY `idx_summary_courseware` (`courseware_id`),
  CONSTRAINT `fk_summary_session` FOREIGN KEY (`session_id`) REFERENCES `class_session` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_summary_courseware` FOREIGN KEY (`courseware_id`) REFERENCES `courseware` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程总结';

-- 9. AI 解析任务表（新增，异步进度跟踪）
CREATE TABLE `ai_parse_task` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'AI 解析任务唯一标识，主键',
  `courseware_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属课件ID，外键',
  `status`        VARCHAR(20)     NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING/RUNNING/SUCCESS/FAILED/PARTIAL',
  `current_page`  INT             NOT NULL DEFAULT 0      COMMENT '当前解析进度页',
  `total_pages`   INT             NULL                    COMMENT '总页数',
  `error_message` TEXT            NULL                    COMMENT '失败原因',
  `started_at`    DATETIME        NULL                    COMMENT '开始时间',
  `finished_at`   DATETIME        NULL                    COMMENT '结束时间',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_ai_task_courseware` (`courseware_id`),
  KEY `idx_ai_task_status` (`status`),
  CONSTRAINT `fk_ai_task_courseware` FOREIGN KEY (`courseware_id`) REFERENCES `courseware` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 课件解析任务';

-- 10. 课堂讨论区发言表（V2 新增）
CREATE TABLE `chat_message` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '发言ID，主键',
  `session_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属课堂，外键',
  `user_id`    BIGINT UNSIGNED NOT NULL                COMMENT '发言人用户ID，外键',
  `page_id`    BIGINT UNSIGNED NULL                    COMMENT '发言时所在页（用于关联知识点，可空）',
  `content`    VARCHAR(1000)   NOT NULL                COMMENT '发言内容（纯文本，禁止 HTML）',
  `status`     VARCHAR(20)     NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL 正常 / DELETED 已删除',
  `deleted_by` BIGINT UNSIGNED NULL                    COMMENT '删除人（老师或管理员）',
  `deleted_at` DATETIME        NULL                    COMMENT '删除时间',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发言时间',
  PRIMARY KEY (`id`),
  -- (session_id, id) 一把索引管两件事：按课堂拉发言、以及 `id > ?` 增量拉新
  KEY `idx_chat_session` (`session_id`, `id`),
  KEY `idx_chat_user` (`user_id`),
  CONSTRAINT `fk_chat_session` FOREIGN KEY (`session_id`) REFERENCES `class_session` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_chat_user`    FOREIGN KEY (`user_id`)    REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课堂讨论区发言';

-- 11. 学生 AI 会话表（V2 新增）
CREATE TABLE `ai_conversation` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话ID，主键',
  `student_id`     BIGINT UNSIGNED NOT NULL                COMMENT '所属学生，外键',
  `title`          VARCHAR(100)    NOT NULL DEFAULT '新会话' COMMENT '会话标题（可重命名）',
  `courseware_id`  BIGINT UNSIGNED NULL                    COMMENT '关联课件（可空，空=自由问答）',
  `session_id`     BIGINT UNSIGNED NULL                    COMMENT '创建时所处课堂（可空=课后创建）',
  `last_active_at` DATETIME        NULL                    COMMENT '最后活跃时间（用于列表排序）',
  `deleted`        TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '软删除标记',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_conv_student` (`student_id`, `deleted`, `last_active_at`),
  CONSTRAINT `fk_conv_student` FOREIGN KEY (`student_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生AI会话';

-- 12. 课堂参与者表（V2 新增）
CREATE TABLE `session_participant` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '参与记录ID，主键',
  `session_id`   BIGINT UNSIGNED NOT NULL                COMMENT '所属课堂，外键',
  `user_id`      BIGINT UNSIGNED NOT NULL                COMMENT '参与用户，外键',
  `role`         VARCHAR(20)     NOT NULL                COMMENT '进入时的角色 TEACHER / STUDENT',
  `joined_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次进入时间',
  `left_at`      DATETIME        NULL                    COMMENT '离开时间，NULL=仍在场',
  `last_seen_at` DATETIME        NULL                    COMMENT '最后心跳时间（用于断线判定）',
  PRIMARY KEY (`id`),
  -- 一个学生在一节课里只留一行：反复进出只更新 left_at / last_seen_at
  UNIQUE KEY `uk_part_session_user` (`session_id`, `user_id`),
  KEY `idx_part_user` (`user_id`),
  CONSTRAINT `fk_part_session` FOREIGN KEY (`session_id`) REFERENCES `class_session` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_part_user`    FOREIGN KEY (`user_id`)    REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课堂参与者（在线名单+出席统计）';

-- 13. 管理操作审计日志表（V2 新增）
CREATE TABLE `admin_audit_log` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '日志ID，主键',
  `admin_id`    BIGINT UNSIGNED NOT NULL                COMMENT '操作人（管理员）用户ID',
  `action`      VARCHAR(50)     NOT NULL                COMMENT '动作码，如 SESSION_FORCE_END',
  `target_type` VARCHAR(30)     NULL                    COMMENT '对象类型 USER / SESSION / COURSEWARE / STORAGE',
  `target_id`   BIGINT UNSIGNED NULL                    COMMENT '对象ID',
  `detail`      VARCHAR(500)    NULL                    COMMENT '细节描述',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_admin`  (`admin_id`, `created_at`),
  KEY `idx_audit_action` (`action`, `created_at`),
  CONSTRAINT `fk_audit_admin` FOREIGN KEY (`admin_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理操作审计日志';

-- =============================================================
-- 【V3】班级体系（与 db/migration/V3__class_group.sql 等价）
-- =============================================================

-- 14. 班级
CREATE TABLE `class_group` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '班级ID，主键',
  `name`        VARCHAR(100)    NOT NULL                COMMENT '班级名（如「计科2301班」）',
  `teacher_id`  BIGINT UNSIGNED NOT NULL                COMMENT '建班教师（班主任），外键',
  `description` VARCHAR(255)    NULL                    COMMENT '备注',
  `deleted`     TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '软删除：1=已删。列表一律带 deleted=0',
  `created_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_group_teacher` (`teacher_id`),
  CONSTRAINT `fk_group_teacher` FOREIGN KEY (`teacher_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级';

-- 15. 班级成员
-- ⚠ 唯一键是「班级 + 人」而不是「人」：一个学生可以同时属于多个班。
CREATE TABLE `class_group_member` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `class_group_id` BIGINT UNSIGNED NOT NULL COMMENT '所属班级，外键',
  `user_id`        BIGINT UNSIGNED NOT NULL COMMENT '成员（学生），外键',
  `joined_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member_group_user` (`class_group_id`, `user_id`),
  KEY `idx_member_user` (`user_id`),
  CONSTRAINT `fk_member_group` FOREIGN KEY (`class_group_id`) REFERENCES `class_group` (`id`),
  CONSTRAINT `fk_member_user`  FOREIGN KEY (`user_id`)        REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='班级成员';

-- 16. 课堂 ↔ 班级（多对多，合班上课用）
-- ⚠ 不是往 class_session 上加 class_group_id：一节课可能同时面向多个班。
CREATE TABLE `session_class_group` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id`     BIGINT UNSIGNED NOT NULL COMMENT '课堂，外键',
  `class_group_id` BIGINT UNSIGNED NOT NULL COMMENT '本节课面向的班级，外键',
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_scg_session_group` (`session_id`, `class_group_id`),
  KEY `idx_scg_group` (`class_group_id`),
  CONSTRAINT `fk_scg_session` FOREIGN KEY (`session_id`)     REFERENCES `class_session` (`id`),
  CONSTRAINT `fk_scg_group`   FOREIGN KEY (`class_group_id`) REFERENCES `class_group` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课堂面向的班级（合班上课用）';

-- 17. 课堂听课名单（开课瞬间的快照）
-- ⚠ 这是**授权判定的唯一依据**：合班时把各班级成员的并集摊平写进来，唯一键负责去重。
CREATE TABLE `session_audience` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `session_id` BIGINT UNSIGNED NOT NULL COMMENT '所属课堂，外键',
  `user_id`    BIGINT UNSIGNED NOT NULL COMMENT '被授权听课的学生，外键',
  `source`     VARCHAR(20)     NOT NULL COMMENT '来源：CLASS(来自班级) / MANUAL(老师单独勾选)',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_audience_session_user` (`session_id`, `user_id`),
  KEY `idx_audience_user` (`user_id`),
  CONSTRAINT `fk_audience_session` FOREIGN KEY (`session_id`) REFERENCES `class_session` (`id`),
  CONSTRAINT `fk_audience_user`    FOREIGN KEY (`user_id`)    REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课堂听课名单（开课快照）';

-- =============================================================
-- 【V4】AI 智能体（与 db/migration/V4__ai_agent.sql 等价）
-- =============================================================

-- 18. AI 生成的文件
--
-- ⚠ 两张新表都**显式写了 COLLATE=utf8mb4_0900_ai_ci**，与其他表（靠库默认）不同。
--   原因是这张表的唯一键 uk_aifile_owner_folder_name **依赖「不区分大小写」**：
--   Java 侧按忽略大小写查重（避免 Windows 上「复习资料.docx / 复习资料.DOCX」撞键报 500），
--   若排序规则变成 utf8mb4_bin，唯一键会变成区分大小写、而 Java 侧still 不区分 ——
--   两边口径不一致，且没有任何测试会失败。写出来就不是「看不见的依赖」了。
CREATE TABLE `ai_generated_file` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `owner_id`      BIGINT UNSIGNED NOT NULL              COMMENT '谁生成的（教师/管理员），外键',
  `folder`        VARCHAR(100)    NOT NULL              COMMENT 'ai-workspace 下的文件夹名（**不是路径**）',
  `filename`      VARCHAR(200)    NOT NULL              COMMENT '文件名（含扩展名）',
  `format`        VARCHAR(10)     NOT NULL              COMMENT 'MD / TXT / DOCX / XLSX',
  `size_bytes`    BIGINT          NOT NULL DEFAULT 0    COMMENT '落盘后的真实字节数',
  `session_id`    BIGINT UNSIGNED NULL                  COMMENT '生成时的上下文：哪节课（可空）',
  `courseware_id` BIGINT UNSIGNED NULL                  COMMENT '生成时的上下文：哪份课件（可空）',
  `prompt`        VARCHAR(1000)   NULL                  COMMENT '老师当时那句指令',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 【为什么要有 updated_at】重新生成同一个文件走的是**改写同一行**（见 upsert），
  -- created_at 不变。列表按它倒序的话，刚刚重新生成的文件会停在几天前的位置，
  -- 老师会以为「没更新成功」。按 updated_at 排才是他要的顺序。
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_aifile_owner_folder_name` (`owner_id`, `folder`, `filename`),
  KEY `idx_aifile_owner_updated` (`owner_id`, `updated_at`),
  KEY `idx_aifile_session` (`session_id`),
  -- ⚠ 只对 owner 建外键，**不给 session_id / courseware_id 建**：
  --   后两者是「顺带记下的上下文」，课件可软删、课堂可清理，
  --   给它们建外键会让「删课件」被历史记录挡住，或把记录级联删掉。
  CONSTRAINT `fk_aifile_owner` FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 智能体生成的文件';

-- 19. AI 智能体任务执行记录
-- 这张表最大的价值是「成本可见」：一眼看到 AI 跑了多少次、烧了多少 token。
CREATE TABLE `ai_agent_run` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `owner_id`          BIGINT UNSIGNED NOT NULL              COMMENT '发起人，外键',
  `instruction`       VARCHAR(1000)   NOT NULL              COMMENT '老师那句话',
  `context_json`      VARCHAR(500)    NULL                  COMMENT '选了哪些课件/课堂/文件夹/格式',
  `steps`             INT             NOT NULL DEFAULT 0    COMMENT '实际用了几轮工具调用',
  `status`            VARCHAR(20)     NOT NULL              COMMENT 'RUNNING / SUCCESS / FAILED / LIMIT',
  `result`            TEXT            NULL                  COMMENT '给老师看的答复正文。前端轮询时读它',
  `error`             VARCHAR(1000)   NULL                  COMMENT '失败在第几轮、为什么',
  `prompt_tokens`     INT             NOT NULL DEFAULT 0,
  `completion_tokens` INT             NOT NULL DEFAULT 0,
  `elapsed_ms`        BIGINT          NOT NULL DEFAULT 0,
  `created_at`        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_airun_owner_created` (`owner_id`, `created_at`),
  KEY `idx_airun_status` (`status`),
  CONSTRAINT `fk_airun_owner` FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 智能体任务执行记录';
