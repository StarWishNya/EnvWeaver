-- 初始化数据脚本
-- 创建默认管理员账户

-- 插入默认管理员用户
-- 用户名: admin
-- 密码: admin123 (BCrypt 加密后的值)
INSERT INTO t_user (username, password, email, role, enabled, created_at, updated_at)
VALUES ('admin', '$2a$10$vnTdr59Hm8233GyT8lke6OnrF8uwmsQ0gv0aa6p93DxpqOeLmSjVO', 'admin@sentinel.com', 'ADMIN', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE username = username;

-- 插入默认普通用户
-- 用户名: user
-- 密码: user123 (BCrypt 加密后的值)
INSERT INTO t_user (username, password, email, role, enabled, created_at, updated_at)
VALUES ('user', '$2a$10$8zoPLPmgs8MULWiEWWE5Cuai9UHpmi96lTL8aU12C/nP4OAEXg9Fu', 'user@sentinel.com', 'USER', 1, NOW(), NOW())
ON DUPLICATE KEY UPDATE username = username;

-- 插入系统配置
INSERT INTO t_system_config (config_key, config_value, value_type, description, created_at, updated_at)
VALUES 
('max_concurrent_tasks', '10', 'INT', '最大并发任务数', NOW(), NOW()),
('task_timeout_minutes', '60', 'INT', '任务超时时间（分钟）', NOW(), NOW()),
('environment_ttl_hours', '24', 'INT', '环境默认存活时间（小时）', NOW(), NOW()),
('enable_auto_cleanup', 'true', 'BOOLEAN', '是否启用自动清理', NOW(), NOW())
ON DUPLICATE KEY UPDATE config_key = config_key;
