-- =============================================================
-- 周五课堂（Friday Class）— V3 迁移：班级体系
--
-- 【做什么】新增 4 张表 + 给 `class_session` 加 1 列。**一条老数据都不搬。**
-- 【幂等】可重复执行，第二遍不报错（含一个「只在第一次执行」的危险动作，见第三节）。
-- 【前置】先备份整库：
--   mysqldump --default-character-set=utf8mb4 -u root -p friday_class > backup_before_v3.sql
-- 【用法】
--   mysql --default-character-set=utf8mb4 -u root -p1234 friday_class < db/migration/V3__class_group.sql
--   ⚠ Windows 上务必带 --default-character-set=utf8mb4，否则中文注释按 GBK 解读成乱码。
--
-- 【为什么不用存储过程】
--   MySQL 8.0/9.x **不支持** `ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的语法）。
--   V2 用存储过程解决，这里改用 `PREPARE` / `EXECUTE` 动态语句 —— 效果一样，
--   但不必碰 `DELIMITER`（那是 mysql 客户端指令，用 GUI 工具或 JDBC 执行时容易踩坑）。
--
-- 【设计意图】见「修改文档1/01_数据库建模_班级体系.md」，此处只摘三条关键决定：
--   1. 一个学生可同时属于多个班 → `class_group_member` 的唯一键是「班级 + 人」；
--   2. 支持合班上课（一节课面向多个班）→ 用关联表 `session_class_group`，不是加一列；
--   3. 授权只看 `session_audience` 这张**开课瞬间的快照** → 判定收口成一行 SQL，
--      不受上课中途换班影响，历史也可追溯。
-- =============================================================

SET NAMES utf8mb4;
USE `friday_class`;

-- =============================================================
-- 一、新增 4 张表
--     `CREATE TABLE IF NOT EXISTS` 天然幂等，重复执行无副作用。
-- =============================================================

-- 1.1 班级
CREATE TABLE IF NOT EXISTS `class_group` (
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

-- 1.2 班级成员
-- ⚠ 唯一键是「班级 + 人」而不是「人」：一个学生可以同时属于多个班。
CREATE TABLE IF NOT EXISTS `class_group_member` (
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

-- 1.3 课堂 ↔ 班级（多对多，合班上课用）
-- ⚠ 不是往 `class_session` 上加 `class_group_id`：一节课可能同时面向多个班，
--   加一列只能表达单班。`idx_scg_group` 支撑教师端「这个班都上过哪些课」。
CREATE TABLE IF NOT EXISTS `session_class_group` (
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

-- 1.4 课堂听课名单（开课瞬间的快照）
-- ⚠ 这是**授权判定的唯一依据**，不是 `session_participant`。
--   `session_participant` 是课后写的「谁真的进过这节课」，用来做事后统计；
--   而「谁能进」必须在开课那一刻就定死，否则先到的学生反而进不来。
-- ⚠ 合班时把各班级成员的**并集**摊平写进来，唯一键负责去重（两班共同的学生只占一行）。
CREATE TABLE IF NOT EXISTS `session_audience` (
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
-- 二、`class_session` 加 `visibility` 列
--
-- 默认 `RESTRICTED`（拒绝优先）：忘了设就是「谁也看不到」这个显眼的 bug，
-- 而不是「所有人都能看到」这个沉默的泄漏。与项目既有安全立场一致。
-- =============================================================

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'class_session' AND COLUMN_NAME = 'visibility'
);

SET @s := IF(@col_exists = 0,
  'ALTER TABLE `class_session`
     ADD COLUMN `visibility` VARCHAR(20) NOT NULL DEFAULT ''RESTRICTED''
       COMMENT ''PUBLIC=所有人可见 / RESTRICTED=仅 session_audience 内可见''',
  'SELECT ''class_session.visibility 已存在，跳过'' AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- =============================================================
-- 三、老数据兼容：历史课堂一律公开（Friday 决策 3）
--
-- ⚠⚠ 本脚本唯一的危险动作 ⚠⚠
-- 只在「列刚刚被这一次执行加上」时才回填。若不加这个判断，
-- 重复执行会把**后来新建的限定课堂也刷成公开** —— 等于把权限一次性全放开，
-- 而且没有任何报错。这个判断就是挡它的。
-- =============================================================

SET @s := IF(@col_exists = 0,
  'UPDATE `class_session` SET `visibility` = ''PUBLIC''',
  'SELECT ''老数据回填已做过，跳过'' AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- =============================================================
-- 四、验证
--     预期：新表 4 / 新列 1 / 公开 = 迁移前的课堂总数 / 限定 0（第二次执行后：
--     新表 4 / 新列 1 / 公开 = 首次执行后的数量 / 限定 = 首次之后新建的课堂数）
-- =============================================================

SELECT '新表' AS 项, COUNT(*) AS 数量 FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME IN ('class_group','class_group_member','session_class_group','session_audience')
UNION ALL
SELECT '新列', COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'class_session' AND COLUMN_NAME = 'visibility'
UNION ALL
SELECT '公开课堂数', COUNT(*) FROM `class_session` WHERE `visibility` = 'PUBLIC'
UNION ALL
SELECT '限定课堂数', COUNT(*) FROM `class_session` WHERE `visibility` = 'RESTRICTED';
