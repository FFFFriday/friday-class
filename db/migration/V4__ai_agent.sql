-- =============================================================
-- 周五课堂（Friday Class）— V4 迁移：AI 智能体
--
-- 【做什么】新增 2 张表。**不改任何现有表，不搬一条老数据。**
-- 【幂等】可重复执行，第二遍不报错（两张表都用 CREATE TABLE IF NOT EXISTS）。
-- 【前置】先备份整库：
--   mysqldump --default-character-set=utf8mb4 -u root -p friday_class > backup_before_v4.sql
-- 【用法】
--   mysql --default-character-set=utf8mb4 -u root -p1234 friday_class < db/migration/V4__ai_agent.sql
--   ⚠ Windows 上务必带 --default-character-set=utf8mb4，否则中文注释按 GBK 解读成乱码。
--
-- 【设计意图】见「修改文档2/A2_AI智能体_技术设计与实施步骤.md」§九，此处只摘两条：
--   1. `ai_generated_file` 记「AI 产出了什么」——有了它才能列出/筛选/显示大小时间，
--      也是**下载接口做 owner 校验的唯一依据**（第 4 条安全原则）。
--   2. `ai_agent_run` 记「AI 跑了多久、烧了多少 token」——对一个成本敏感的项目，
--      **让成本可见**比省一两次调用有用得多。
--
-- 【为什么两张表要分家，不合成一张】
--   一次任务可能一个文件都没产出（失败 / 步数耗尽），也可能产出多个文件；
--   反过来，文件的生命周期比任务长（任务记录可以清理，文件还要下载）。
--   合成一张的话，「列出我的文件」就得从任务记录里反解，且删任务会连带丢文件索引。
--
-- 【⚠ 列类型必须与 `user.id` 对齐】
--   `user.id` 是 `BIGINT UNSIGNED`。外键两侧类型不一致时建表会直接失败
--   （errno 3780），而不是给个警告。两个 owner_id 都必须是 `BIGINT UNSIGNED`。
-- =============================================================

SET NAMES utf8mb4;
USE `friday_class`;

-- =============================================================
-- 一、AI 生成的文件（产出物索引）
-- =============================================================

-- ⚠ `folder` 存的是**文件夹名，不是路径**。
--   路径由服务端拼（`ai-workspace/u{owner_id}/{folder}/{filename}`），
--   这一条本身就是最有效的一道防线：`..`、`C:\`、`/etc/` 这些根本进不来，
--   因为它们不是「一个名字」。详细七条校验见 A1 §4.4。
CREATE TABLE IF NOT EXISTS `ai_generated_file` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `owner_id`      BIGINT UNSIGNED NOT NULL              COMMENT '谁生成的（教师/管理员），外键',
  `folder`        VARCHAR(100)    NOT NULL              COMMENT 'ai-workspace 下的文件夹名（**不是路径**）',
  `filename`      VARCHAR(200)    NOT NULL              COMMENT '文件名（含扩展名）',
  `format`        VARCHAR(10)     NOT NULL              COMMENT 'MD / TXT / DOCX / XLSX',
  `size_bytes`    BIGINT          NOT NULL DEFAULT 0    COMMENT '落盘后的真实字节数（写入后回读，不是估算）',
  `session_id`    BIGINT UNSIGNED NULL                  COMMENT '生成时的上下文：哪节课（可空）',
  `courseware_id` BIGINT UNSIGNED NULL                  COMMENT '生成时的上下文：哪份课件（可空）',
  `prompt`        VARCHAR(1000)   NULL                  COMMENT '老师当时那句指令（便于回溯「这文件是怎么来的」）',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 【为什么要有 updated_at】重新生成同一个文件走的是「改写同一行」（见 WorkspaceFileService.upsert），
  -- created_at 不变。列表若按它倒序，刚刚重新生成的文件会停在几天前的位置，
  -- 老师会以为「没更新成功」。按 updated_at 排才是他要的顺序。
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  -- 文件名长度按 utf8mb4 算：200 字符 × 4 字节 + 100 × 4 + owner 8 字节 ≈ 1208 字节，
  -- 未超 InnoDB 的 3072 字节索引上限，可以直接建组合索引。
  UNIQUE KEY `uk_aifile_owner_folder_name` (`owner_id`, `folder`, `filename`),
  KEY `idx_aifile_owner_updated` (`owner_id`, `updated_at`),
  KEY `idx_aifile_session` (`session_id`),
  -- ⚠ 只对 owner 建外键，**不给 session_id / courseware_id 建**：
  --   后两者是「顺带记下的上下文」，课件可以软删除、课堂可以清理，
  --   给它们建外键会让「删课件」被这两条历史记录挡住，或者反过来把记录级联删掉。
  --   owner 不同：它是访问控制的依据，必须真实存在。
  CONSTRAINT `fk_aifile_owner` FOREIGN KEY (`owner_id`) REFERENCES `user` (`id`)
  -- ⚠ 显式写死排序规则，与库里其他表（靠默认）不同。
  --   原因：上面的唯一键 **依赖「不区分大小写」** —— Java 侧按忽略大小写查重
  --   （避免 Windows 上「复习资料.docx / 复习资料.DOCX」撞键报 500）。
  --   若排序规则变成 utf8mb4_bin，唯一键会变成区分大小写而 Java 侧仍不区分，
  --   两边口径不一致，且没有任何测试会失败。写出来就不是「看不见的依赖」了。
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 智能体生成的文件';

-- =============================================================
-- 二、AI 智能体任务执行记录
-- =============================================================

CREATE TABLE IF NOT EXISTS `ai_agent_run` (
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

-- =============================================================
-- 三、补齐 `ai_agent_run.result` 列（幂等）
--
-- 上面第一节的 CREATE TABLE 里已经带了这一列，全新库走到这里会直接跳过。
-- 这一段是给「在本文件定型之前就执行过一次」的库补列的 —— 开发期确实发生过。
--
-- ⚠ MySQL 8.0/9.x **不支持** `ADD COLUMN IF NOT EXISTS`（那是 MariaDB 的语法），
--   所以沿用 V3 的 `PREPARE` / `EXECUTE` 动态语句，效果一样且不必碰 DELIMITER。
--
-- 【为什么答复正文要落库，而不是留在内存】
--   前端靠轮询取结果。只放内存的话，服务一重启，那条已经跑完的任务
--   就变成「状态是 SUCCESS、但一个字都读不出来」——用户看到的是空白。
-- =============================================================

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_agent_run' AND COLUMN_NAME = 'result'
);

SET @s := IF(@col_exists = 0,
  'ALTER TABLE `ai_agent_run`
     ADD COLUMN `result` TEXT NULL COMMENT ''给老师看的答复正文。前端轮询时读它'' AFTER `status`',
  'SELECT ''ai_agent_run.result 已存在，跳过'' AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- =============================================================
-- 三之二、补齐 `ai_generated_file.updated_at` 列与配套索引（幂等）
--
-- 同样：第一节的 CREATE TABLE 已带该列，这一段是给更早版本执行过的库补的。
-- 顺序很重要：**先加列、再建索引** —— 在列存在之前建索引会直接报错。
-- =============================================================

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generated_file' AND COLUMN_NAME = 'updated_at'
);

SET @s := IF(@col_exists = 0,
  'ALTER TABLE `ai_generated_file`
     ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
       ON UPDATE CURRENT_TIMESTAMP AFTER `created_at`',
  'SELECT ''ai_generated_file.updated_at 已存在，跳过'' AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

SET @idx_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generated_file'
    AND INDEX_NAME = 'idx_aifile_owner_updated'
);

SET @s := IF(@idx_exists = 0,
  'ALTER TABLE `ai_generated_file` ADD KEY `idx_aifile_owner_updated` (`owner_id`, `updated_at`)',
  'SELECT ''idx_aifile_owner_updated 已存在，跳过'' AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- 列表排序从 created_at 换成了 updated_at，旧索引就没有用了。
-- 留着只会让每次 INSERT 多维护一棵索引树，且容易让人以为排序还走它。
SET @old_idx := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_generated_file'
    AND INDEX_NAME = 'idx_aifile_owner_created'
);

SET @s := IF(@old_idx > 0,
  'ALTER TABLE `ai_generated_file` DROP KEY `idx_aifile_owner_created`',
  'SELECT ''idx_aifile_owner_created 不存在，跳过'' AS msg');
PREPARE st FROM @s; EXECUTE st; DEALLOCATE PREPARE st;

-- =============================================================
-- 四、验证
--     预期：新表 2；两张表各自 0 行（首次执行后）。
-- =============================================================

SELECT '新表' AS 项, COUNT(*) AS 数量 FROM information_schema.TABLES
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME IN ('ai_generated_file', 'ai_agent_run')
UNION ALL
SELECT 'ai_generated_file 行数', COUNT(*) FROM `ai_generated_file`
UNION ALL
SELECT 'ai_agent_run 行数', COUNT(*) FROM `ai_agent_run`;
