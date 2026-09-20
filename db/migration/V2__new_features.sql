-- =============================================================
-- 周五课堂（Friday Class）— V2 迁移：新增需求（M1–M6）
--
-- 【做什么】新增 4 张表 + 给 3 张已有表加列。**一条老数据都不搬。**
-- 【幂等】可重复执行，第二遍不报错（见下方说明）。
-- 【前置】先备份整库：
--   mysqldump --default-character-set=utf8mb4 -u root -p friday_class > backup_before_v2.sql
-- 【用法】
--   mysql --default-character-set=utf8mb4 -u root -p1234 friday_class < db/migration/V2__new_features.sql
--   ⚠ Windows 上务必带 --default-character-set=utf8mb4，否则中文注释按 GBK 解读成乱码。
--
-- 【为什么需要存储过程】
--   MySQL 8.0/9.x **不支持** `ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的语法）。
--   要做到「重复执行不报错」，只能先查 information_schema 再动态拼 ALTER。
--   `DELIMITER` 是 mysql 客户端指令（不是服务端语法），用 CLI 执行时正常生效。
-- =============================================================

SET NAMES utf8mb4;
USE `friday_class`;

-- =============================================================
-- 一、新增 4 张表
--     `CREATE TABLE IF NOT EXISTS` 天然幂等，重复执行无副作用。
-- =============================================================

-- 1.1 课堂讨论区发言（M2）
CREATE TABLE IF NOT EXISTS `chat_message` (
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

-- 1.2 学生 AI 会话（M4）
CREATE TABLE IF NOT EXISTS `ai_conversation` (
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

-- 1.3 课堂参与者（M1 在线名单 / M5 出席 / M6 踢人）
CREATE TABLE IF NOT EXISTS `session_participant` (
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

-- 1.4 管理操作审计日志（M6）
CREATE TABLE IF NOT EXISTS `admin_audit_log` (
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
-- 二、给已有表加列（幂等）
-- =============================================================

DROP PROCEDURE IF EXISTS `fc_add_column`;
DELIMITER $$
CREATE PROCEDURE `fc_add_column`(
  IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_col
  ) THEN
    SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$
DELIMITER ;

-- 2.1 class_session：支持暂停、记录结束方（M6）
CALL fc_add_column('class_session', 'paused_at',
     '`paused_at` DATETIME NULL COMMENT ''暂停时间，NULL=未暂停''');
CALL fc_add_column('class_session', 'ended_by',
     '`ended_by` BIGINT UNSIGNED NULL COMMENT ''结束人，NULL=老师自己下课''');
CALL fc_add_column('class_session', 'end_reason',
     '`end_reason` VARCHAR(100) NULL COMMENT ''结束原因，如 ADMIN_FORCE''');

-- 2.2 qa_record：挂上会话与课件（M4 / M5）
--     ⚠ 刻意**不加外键**：courseware 是可以被管理员硬删的（M6），
--       若这里加 FK RESTRICT，删课件会直接被约束挡住。
CALL fc_add_column('qa_record', 'conversation_id',
     '`conversation_id` BIGINT UNSIGNED NULL COMMENT ''所属AI会话''');
CALL fc_add_column('qa_record', 'courseware_id',
     '`courseware_id` BIGINT UNSIGNED NULL COMMENT ''所属课件（直连，避免两跳join）''');

-- 2.3 course_summary：支持异步生成（M5）
CALL fc_add_column('course_summary', 'status',
     '`status` VARCHAR(20) NOT NULL DEFAULT ''SUCCESS'' COMMENT ''PENDING/RUNNING/SUCCESS/FAILED''');
CALL fc_add_column('course_summary', 'error_message',
     '`error_message` TEXT NULL COMMENT ''失败原因''');
CALL fc_add_column('course_summary', 'source',
     '`source` VARCHAR(20) NOT NULL DEFAULT ''SESSION_CHAT'' COMMENT ''总结来源''');

DROP PROCEDURE IF EXISTS `fc_add_column`;


-- =============================================================
-- 三、加索引（幂等）
-- =============================================================

DROP PROCEDURE IF EXISTS `fc_add_index`;
DELIMITER $$
CREATE PROCEDURE `fc_add_index`(
  IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index
  ) THEN
    SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_ddl);
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$
DELIMITER ;

CALL fc_add_index('qa_record', 'idx_qa_conversation',
     'KEY `idx_qa_conversation` (`conversation_id`, `id`)');
CALL fc_add_index('qa_record', 'idx_qa_session_student',
     'KEY `idx_qa_session_student` (`session_id`, `student_id`)');

DROP PROCEDURE IF EXISTS `fc_add_index`;


-- =============================================================
-- 四、page_id 改可空（本套迁移最关键的一条）
--
-- 学生**下课后**问 AI 时不在任何一页上，没有 page_id 可填。
-- 若保持 NOT NULL，课后问答会直接落库失败 —— 而「课后也能用 AI」
-- 是需求 8 的明确要求。MODIFY 本身幂等，重复执行无副作用。
-- =============================================================

ALTER TABLE `qa_record`
  MODIFY COLUMN `page_id` BIGINT UNSIGNED NULL COMMENT '所属页，NULL=课后提问（不在任何页上）';

-- ⚠ 4.2 `session_id` **同样必须可空** —— 初版遗漏，2026-09-21 补。
--
-- 「课后提问」既不在任何课堂上、也不在任何页上。
-- 只把 page_id 改成可空是**不够的**：session_id 仍是 NOT NULL 时，
-- 课后提问会在落库这一步直接 500。
-- 实测症状：`DataIntegrityViolationException: Column 'session_id' cannot be null`，
-- 接口返回「服务器内部错误」，而前端只会看到一个笼统的 500 —— 极难反查到是列约束问题。
--
-- MODIFY 本身幂等，重复执行无副作用。
ALTER TABLE `qa_record`
  MODIFY COLUMN `session_id` BIGINT UNSIGNED NULL COMMENT '所属课堂，NULL=课后提问（不挂在任何课堂下）';


-- =============================================================
-- 五、user 表加「禁用」标记（M6 管理端的账号禁用/启用）
--
-- ⚠️ 初版计划书也漏了这一列（同 session_id 那次）：M6 的接口列表里有
-- 「禁用 / 启用账号」，但 user 表只有 deleted（软删除），没有「禁用」这个维度。
-- 两者不能混用：禁用是**临时**的（可恢复、账号还在、数据都在），
-- 软删除是**移除**（列表里不再出现）。用 deleted 兼职禁用的话，
-- 「启用」就得把 deleted 改回 0，等于把删除也一起撤销了。
-- =============================================================

DROP PROCEDURE IF EXISTS `fc_add_column`;
DELIMITER $$
CREATE PROCEDURE `fc_add_column`(
  IN p_table VARCHAR(64), IN p_col VARCHAR(64), IN p_ddl TEXT)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_col
  ) THEN
    SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
    PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;
  END IF;
END$$
DELIMITER ;

CALL fc_add_column('user', 'disabled',
     '`disabled` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''禁用标记：1=禁止登录，但账号与数据都保留''');

DROP PROCEDURE IF EXISTS `fc_add_column`;


-- =============================================================
-- 六、验证输出
-- =============================================================

SELECT '=== 新增的 4 张表 ===' AS `检查项`;
SELECT TABLE_NAME AS `表名`, TABLE_COMMENT AS `说明`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('chat_message','ai_conversation','session_participant','admin_audit_log');

SELECT '=== qa_record.page_id 是否已可空（IS_NULLABLE 应为 YES）===' AS `检查项`;
SELECT COLUMN_NAME AS `列名`, IS_NULLABLE AS `可空`, COLUMN_TYPE AS `类型`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'qa_record'
  AND COLUMN_NAME IN ('page_id','conversation_id','courseware_id');

SELECT '=== 老数据未受影响（问答记录总数）===' AS `检查项`;
SELECT COUNT(*) AS `qa_record 行数` FROM `qa_record`;
