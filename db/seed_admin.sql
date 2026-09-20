-- =============================================================
-- 周五课堂 — 管理员账号预置脚本
--
-- 【为什么需要这个】
-- 与教师账号同理（见 db/seed_teacher.sql）：自助注册接口只能建学生，
-- 角色由服务端写死为 STUDENT，**不接受客户端传入 role**。
-- 若允许注册时自选角色，任何人都能注册成管理员，从而管理全平台账号。
-- 所以 ADMIN 账号必须由数据库侧预置。
--
-- 【用法】
--   命令行： mysql --default-character-set=utf8mb4 -u root -p1234 friday_class < db/seed_admin.sql
--   客户端： SOURCE db/seed_admin.sql;
--
--   ⚠ Windows 上务必带 --default-character-set=utf8mb4，否则中文昵称会按 GBK
--     解读成乱码。这条坑在 seed_teacher.sql 里已经踩过一次。
--
-- 【预置的账号】
--   用户名 admin     密码 admin123456     角色 ADMIN     昵称「系统管理员」
--
--   ⚠ 首次登录后请在「账户中心」修改密码。
--   ⚠ 本仓库是公开的：这个初始密码就写在注释里。
--     正式部署前必须改密码，或直接删掉这个账号。
--
-- 【为什么哈希是 $2b$ 而不是 $2a$】
--   $2a$ / $2b$ / $2y$ 三个前缀 Spring Security 的 BCryptPasswordEncoder
--   都能验通过，只是生成器不同（$2a$ 是 Java jBCrypt 系，$2b$ 是主流实现）。
--   这里是本地用 bcryptjs 强度 10 生成的，已验证 compare(明文, 哈希) 为 true。
--   不改写成 $2a$ —— 改前缀会让哈希失效。
-- =============================================================

SET NAMES utf8mb4;

USE `friday_class`;

-- ON DUPLICATE KEY UPDATE 只升角色、**不覆盖密码**：脚本可反复执行，
-- 不会把管理员已经改过的密码重置回初始值。（与 seed_teacher.sql 同款处理）
INSERT INTO `user` (`username`, `password_hash`, `role`, `nickname`)
VALUES
  (
    'admin',
    '$2b$10$wFZ03fumJp1Le9IpvVe5F.0wFj9vU1kMJ8Rv9bTKVDIcyyDH1Iuly',
    'ADMIN',
    '系统管理员'
  )
ON DUPLICATE KEY UPDATE `role` = 'ADMIN';

-- 确认结果
SELECT `id`, `username`, `role`, `nickname`, `token_version` FROM `user` WHERE `role` = 'ADMIN';
