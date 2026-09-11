-- =============================================================
-- 周五课堂（Friday Class）— 数据库建表脚本
-- MySQL 9.5（语法兼容 8.0）· InnoDB · utf8mb4
--
-- 说明：本脚本在 SRS 数据字典（7 实体）基础上审查后扩展为 9 张表：
--   · 新增「课堂/直播会话 class_session」——F003 直播、翻页同步、F006 学情统计、
--     F005 课后总结都需挂在「一次上课」上；
--   · 新增「AI 解析任务 ai_parse_task」——F002 是异步逐页调用 DeepSeek，需跟踪进度/失败重试；
--   · 改造 qa_record / course_summary 补充课堂关联；
--   · 全表补 created_at/updated_at 时间戳、deleted 软删除、status 状态字段。
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
  `status`           VARCHAR(20)     NOT NULL DEFAULT 'NOT_STARTED' COMMENT '直播状态：NOT_STARTED/LIVE/ENDED',
  `stream_push_url`  VARCHAR(500)    NULL                    COMMENT '推流地址（直播方案待定，预留）',
  `stream_pull_url`  VARCHAR(500)    NULL                    COMMENT '拉流地址（直播方案待定，预留）',
  `current_page`     INT             NULL                    COMMENT '当前页码（随翻页广播更新；迟到/重连的学生靠它恢复上下文）',
  `started_at`       DATETIME        NULL                    COMMENT '开始时间',
  `ended_at`         DATETIME        NULL                    COMMENT '结束时间',
  `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted`          TINYINT(1)      NOT NULL DEFAULT 0      COMMENT '软删除标记',
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
  `session_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属课堂ID，外键',
  `student_id` BIGINT UNSIGNED NOT NULL                COMMENT '提问学生用户ID，外键',
  `page_id`    BIGINT UNSIGNED NOT NULL                COMMENT '提问时所在页ID，外键',
  `question`   TEXT            NOT NULL                COMMENT '学生提问文本（≤500 字）',
  `answer`     TEXT            NULL                    COMMENT 'AI 回答文本',
  `status`     VARCHAR(20)     NOT NULL DEFAULT 'SUCCESS' COMMENT '问答状态：SUCCESS 已作答 / FAILED 调用失败（便于学情统计区分）',
  `asked_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_qa_session` (`session_id`),
  KEY `idx_qa_student` (`student_id`),
  KEY `idx_qa_page` (`page_id`),
  CONSTRAINT `fk_qa_session` FOREIGN KEY (`session_id`) REFERENCES `class_session` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_qa_student` FOREIGN KEY (`student_id`) REFERENCES `user` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_qa_page` FOREIGN KEY (`page_id`) REFERENCES `courseware_page` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='问答记录';

-- 8. 课程总结表（补充课堂关联）
CREATE TABLE `course_summary` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '课程总结唯一标识，主键',
  `session_id`    BIGINT UNSIGNED NOT NULL                COMMENT '所属课堂ID，外键',
  `courseware_id` BIGINT UNSIGNED NOT NULL                COMMENT '所属课件ID，外键（冗余，便于按课件查询）',
  `content`       TEXT            NULL                    COMMENT '课程总结文本',
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
