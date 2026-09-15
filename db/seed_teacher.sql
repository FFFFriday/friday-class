-- =============================================================
-- 周五课堂 — 教师账号预置脚本
--
-- 【为什么需要这个】
-- 自助注册接口（POST /api/auth/register）只能创建学生账号，不接收 role 字段。
-- 这是有意的安全设计：若允许注册时自选角色，任何人都能注册成教师，
-- 从而获得上传课件、创建课堂等教师权限（自助提权）。
-- 因此教师账号必须由管理员在数据库侧预置。
--
-- 【用法】
--   命令行： mysql --default-character-set=utf8mb4 -u root -p friday_class < db/seed_teacher.sql
--   客户端： SOURCE db/seed_teacher.sql;
--
--   ⚠ Windows 上务必带 --default-character-set=utf8mb4（或确认脚本顶部的
--     SET NAMES utf8mb4 已生效），否则客户端按 GBK 解读本文件的 UTF-8 字节，
--     中文昵称会存成乱码（如「演示教师」变成「婕旂ず鏁欏笀」）。
--
-- 【预置的账号】
--   用户名 teacher     密码 teacher123     角色 TEACHER   昵称「演示教师」
--   用户名 teacher2    密码 teacher2123    角色 TEACHER   昵称「教师二」
--   用户名 teacher3    密码 teacher3123    角色 TEACHER   昵称「教师三」
--
--   teacher2 / teacher3 是给「多个老师同时开课」的演示场景准备的：
--   本平台的「正在直播」区分「我的课堂」与「其他老师的课堂」，
--   只有一个教师账号时那半边永远是空的，看不出效果。
--
--   ⚠ 首次登录后请立即在「账户中心」修改密码。
--   ⚠ 本仓库是公开的：这三个账号的初始密码都写在注释里。
--     正式部署前必须改密码或直接删掉这三个账号。
--
-- 【想用自己的密码】
--   方式一（推荐）：先去注册页注册一个学生账号，再执行：
--     UPDATE `user` SET `role` = 'TEACHER' WHERE `username` = '你的用户名';
--   方式二：自己算 BCrypt 哈希替换下面的 password_hash（强度 10）。
-- =============================================================

-- 声明客户端发送的字符集，防止中文按 GBK 解读成乱码
SET NAMES utf8mb4;

USE `friday_class`;

-- ON DUPLICATE KEY UPDATE 只改角色、**不覆盖密码**：脚本可以反复执行，
-- 不会把管理员已经改过的密码重置回初始值。
INSERT INTO `user` (`username`, `password_hash`, `role`, `nickname`)
VALUES
  (
    'teacher',
    '$2a$10$fDSVp7QGE0ZogdELFcx8tOJPRa2zFQ2C7pWEgfPCaVnPvzN98fgm.',
    'TEACHER',
    '演示教师'
  ),
  (
    'teacher2',
    '$2a$10$HGO6Ooobi9MAywd6PvNLT.sRhk3C29NzDvQUcF8pbpsI2yupWgrsS',
    'TEACHER',
    '教师二'
  ),
  (
    'teacher3',
    '$2a$10$gc4YCsyf0brdwb/ba3Yboez2Fld4.AfqVwSU3436GzxWfXJu1EBDy',
    'TEACHER',
    '教师三'
  )
ON DUPLICATE KEY UPDATE `role` = 'TEACHER';

-- 确认结果
SELECT `id`, `username`, `role`, `nickname`, `token_version` FROM `user` WHERE `role` = 'TEACHER';
