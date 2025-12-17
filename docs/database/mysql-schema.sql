-- ============================================================
-- Sentinel 数据库 Schema (精简版 v2.0)
-- 适用于：MySQL 8.0+
-- 说明：MVP 版本共 6 张核心表（含用户表）
-- ============================================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS sentinel
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE sentinel;

-- ============================================================
-- 1. 任务表 (t_task)
-- 说明：记录 CI 系统提交的测试任务
-- ============================================================
CREATE TABLE t_task (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT  COMMENT '任务ID',
    task_code       VARCHAR(64)     NOT NULL UNIQUE             COMMENT '任务编码(如 build-1234)',
    service_id      VARCHAR(64)     NOT NULL                    COMMENT '服务标识(如 user-service)',
    test_type       VARCHAR(32)     NOT NULL                    COMMENT '测试类型: UNIT_TEST, INTEGRATION_TEST, E2E_TEST',
    dependencies    JSON                                        COMMENT '依赖列表: ["mysql:8.0", "redis:7.0"]',
    status          VARCHAR(32)     NOT NULL DEFAULT 'PENDING'  COMMENT '状态: PENDING, QUEUED, RUNNING, SUCCESS, FAILED, CANCELLED',
    priority        INT             NOT NULL DEFAULT 5          COMMENT '优先级: 1-10, 数字越大优先级越高',
    environment_id  BIGINT                                      COMMENT '关联的环境ID',
    error_message   TEXT                                        COMMENT '错误信息',
    started_at      DATETIME                                    COMMENT '开始时间',
    completed_at    DATETIME                                    COMMENT '完成时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_task_status (status),
    INDEX idx_task_service (service_id),
    INDEX idx_task_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务表';

-- ============================================================
-- 2. 环境表 (t_environment)
-- 说明：记录测试环境实例
-- ============================================================
CREATE TABLE t_environment (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT  COMMENT '环境ID',
    env_code        VARCHAR(64)     NOT NULL UNIQUE             COMMENT '环境编码(如 env-abc123)',
    task_id         BIGINT                                      COMMENT '关联的任务ID',
    status          VARCHAR(32)     NOT NULL DEFAULT 'CREATING' COMMENT '状态: CREATING, READY, BUSY, RELEASING, IDLE, ERROR',
    compose_content TEXT                                        COMMENT '生成的 docker-compose.yml 内容',
    access_url      VARCHAR(255)                                COMMENT '访问地址',
    port_mappings   JSON                                        COMMENT '端口映射: {"mysql": 33061, "redis": 63791}',
    resource_usage  JSON                                        COMMENT '资源使用: {"cpu": 0.5, "memory": 512}',
    expires_at      DATETIME                                    COMMENT '过期时间(用于自动回收)',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_env_status (status),
    INDEX idx_env_task (task_id),
    INDEX idx_env_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='环境表';

-- ============================================================
-- 3. 容器表 (t_container)
-- 说明：记录环境中的容器实例
-- ============================================================
CREATE TABLE t_container (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT  COMMENT '容器记录ID',
    environment_id  BIGINT          NOT NULL                    COMMENT '所属环境ID',
    container_id    VARCHAR(64)     NOT NULL                    COMMENT 'Docker 容器ID',
    container_name  VARCHAR(128)    NOT NULL                    COMMENT '容器名称',
    image           VARCHAR(255)    NOT NULL                    COMMENT '镜像名称(如 mysql:8.0)',
    status          VARCHAR(32)     NOT NULL DEFAULT 'CREATED'  COMMENT '状态: CREATED, RUNNING, STOPPED, ERROR',
    port_mapping    VARCHAR(64)                                 COMMENT '端口映射(如 3306:33061)',
    health_status   VARCHAR(32)     DEFAULT 'UNKNOWN'           COMMENT '健康状态: UNKNOWN, HEALTHY, UNHEALTHY',
    last_log        TEXT                                        COMMENT '最后日志片段(用于快速诊断)',
    started_at      DATETIME                                    COMMENT '启动时间',
    stopped_at      DATETIME                                    COMMENT '停止时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_container_env (environment_id),
    INDEX idx_container_status (status),
    FOREIGN KEY (environment_id) REFERENCES t_environment(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='容器表';

-- ============================================================
-- 4. 诊断缓存表 (t_diagnosis_cache)
-- 说明：缓存 AI 诊断结果，避免重复分析相同错误
-- ============================================================
CREATE TABLE t_diagnosis_cache (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT  COMMENT '缓存ID',
    log_hash        CHAR(32)        NOT NULL UNIQUE             COMMENT '日志内容的 MD5 哈希',
    log_sample      TEXT            NOT NULL                    COMMENT '日志样本(前 500 字符)',
    root_cause      VARCHAR(500)    NOT NULL                    COMMENT '根本原因(AI 分析结果)',
    possible_reasons JSON                                       COMMENT '可能原因列表',
    solutions       JSON                                        COMMENT '解决方案列表',
    hit_count       INT             NOT NULL DEFAULT 1          COMMENT '命中次数',
    last_hit_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最后命中时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    
    INDEX idx_cache_hash (log_hash),
    INDEX idx_cache_hit (hit_count DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='诊断缓存表';

-- ============================================================
-- 5. 系统配置表 (t_system_config)
-- 说明：存储系统运行参数
-- ============================================================
CREATE TABLE t_system_config (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT  COMMENT '配置ID',
    config_key      VARCHAR(64)     NOT NULL UNIQUE             COMMENT '配置键',
    config_value    TEXT            NOT NULL                    COMMENT '配置值',
    value_type      VARCHAR(32)     NOT NULL DEFAULT 'STRING'   COMMENT '值类型: STRING, INT, BOOLEAN, JSON',
    description     VARCHAR(255)                                COMMENT '配置说明',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='系统配置表';

-- ============================================================
-- 6. 用户表 (t_user)
-- 说明：记录系统用户信息
-- ============================================================
CREATE TABLE t_user (
    id              BIGINT          PRIMARY KEY AUTO_INCREMENT  COMMENT '用户ID',
    username        VARCHAR(50)     NOT NULL UNIQUE             COMMENT '用户名',
    password        VARCHAR(255)    NOT NULL                    COMMENT '密码(BCrypt加密)',
    email           VARCHAR(100)                                COMMENT '邮箱',
    role            VARCHAR(32)     NOT NULL DEFAULT 'USER'     COMMENT '角色: ADMIN, USER',
    enabled         TINYINT(1)      NOT NULL DEFAULT 1          COMMENT '是否启用: 0-禁用, 1-启用',
    last_login_at   DATETIME                                    COMMENT '最后登录时间',
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    INDEX idx_user_username (username),
    INDEX idx_user_role (role)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';

-- ============================================================
-- 初始化配置数据
-- ============================================================
-- 初始化管理员用户（密码: admin123，BCrypt 加密）
INSERT INTO t_user (username, password, email, role) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5EH', 'admin@sentinel.local', 'ADMIN');

INSERT INTO t_system_config (config_key, config_value, value_type, description) VALUES
('max_concurrent_environments', '10', 'INT', '最大并发环境数'),
('memory_threshold_high', '0.8', 'STRING', '内存高水位阈值'),
('memory_threshold_low', '0.6', 'STRING', '内存低水位阈值'),
('queue_threshold', '3', 'INT', '队列长度阈值'),
('environment_timeout_minutes', '30', 'INT', '环境超时时间(分钟)'),
('container_startup_timeout_seconds', '120', 'INT', '容器启动超时时间(秒)'),
('ollama_timeout_seconds', '30', 'INT', 'Ollama 调用超时时间(秒)'),
('diagnosis_cache_ttl_days', '90', 'INT', '诊断缓存保留天数'),
('metrics_collect_interval_seconds', '10', 'INT', '指标采集间隔(秒)');

-- ============================================================
-- 视图：活跃任务统计
-- ============================================================
CREATE OR REPLACE VIEW v_task_stats AS
SELECT 
    status,
    COUNT(*) as count,
    DATE(created_at) as date
FROM t_task
WHERE created_at >= DATE_SUB(CURRENT_DATE, INTERVAL 7 DAY)
GROUP BY status, DATE(created_at);

-- ============================================================
-- 视图：环境使用情况
-- ============================================================
CREATE OR REPLACE VIEW v_environment_usage AS
SELECT 
    status,
    COUNT(*) as count
FROM t_environment
GROUP BY status;

-- ============================================================
-- 存储过程：清理过期环境
-- ============================================================
DELIMITER //
CREATE PROCEDURE sp_cleanup_expired_environments()
BEGIN
    -- 标记过期环境为待释放
    UPDATE t_environment 
    SET status = 'RELEASING'
    WHERE status IN ('IDLE', 'READY') 
      AND expires_at < NOW();
    
    -- 返回受影响的行数
    SELECT ROW_COUNT() as cleaned_count;
END //
DELIMITER ;

-- ============================================================
-- 存储过程：清理旧诊断缓存
-- ============================================================
DELIMITER //
CREATE PROCEDURE sp_cleanup_old_diagnosis_cache(IN days_to_keep INT)
BEGIN
    DELETE FROM t_diagnosis_cache 
    WHERE last_hit_at < DATE_SUB(NOW(), INTERVAL days_to_keep DAY)
      AND hit_count < 5;  -- 保留高频命中的缓存
    
    SELECT ROW_COUNT() as deleted_count;
END //
DELIMITER ;
