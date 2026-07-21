-- ===================== 操作审计日志表 =====================
-- 记录用户对系统的关键操作（登录/登出/增删改配置/同步控制等）
-- 包含：操作者信息、操作类型、目标资源、客户端 IP、请求信息、执行结果
CREATE TABLE IF NOT EXISTS `operation_log` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT       DEFAULT NULL COMMENT '操作者用户 ID',
    `username`        VARCHAR(64)  DEFAULT NULL COMMENT '操作者用户名',
    `operation_type`  VARCHAR(50)  NOT NULL COMMENT '操作类型(LOGIN/LOGOUT/REGISTER/MAPPING_ADD/MAPPING_REMOVE/MAPPING_RELOAD/MAPPING_UPDATE/RESYNC/TEST_CONNECTION等)',
    `operation_desc`  VARCHAR(200) DEFAULT NULL COMMENT '操作描述',
    `target_resource` VARCHAR(500) DEFAULT NULL COMMENT '目标资源(如 instanceKey)',
    `client_ip`       VARCHAR(64)  DEFAULT NULL COMMENT '客户端 IP',
    `request_url`     VARCHAR(500) DEFAULT NULL COMMENT '请求 URL',
    `request_method`  VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP 方法',
    `request_params`  TEXT         DEFAULT NULL COMMENT '请求参数摘要(截断到2000字符)',
    `result_status`   VARCHAR(20)  DEFAULT NULL COMMENT '执行结果(SUCCESS/FAILURE)',
    `error_msg`       VARCHAR(1000) DEFAULT NULL COMMENT '错误信息(失败时)',
    `duration_ms`     BIGINT       DEFAULT NULL COMMENT '执行耗时(毫秒)',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_id`      (`user_id`),
    KEY `idx_create_time`  (`create_time`),
    KEY `idx_operation_type` (`operation_type`),
    KEY `idx_client_ip`    (`client_ip`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';

-- sync_progress 表增加 user_id 列（配置按用户区分）
-- 注意：已存在的记录 user_id 为 NULL，表示全局共享（所有用户可见）
ALTER TABLE `sync_progress` ADD COLUMN IF NOT EXISTS `user_id` BIGINT DEFAULT NULL COMMENT '所属用户ID(NULL=全局)' AFTER `id`;
ALTER TABLE `sync_progress` ADD INDEX IF NOT EXISTS `idx_user_id` (`user_id`);
