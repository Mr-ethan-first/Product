#!/bin/bash
# =============================================================================
# DRPlatform 灾备同步服务 - Linux 一键安装脚本
# 用法:  sudo bash install.sh
#
# 功能:
#   - 交互式配置 MySQL 账号密码（无默认值，必须手动输入）
#   - 自动安装 Java 17+ / Nginx（如未安装）
#   - 创建数据库、部署 jar、生成配置文件
#   - 配置 Nginx 反向代理、systemd 服务
#   - 启动服务并验证
# =============================================================================
set -euo pipefail

# ===================== 颜色定义 =====================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

info()    { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }
step()    { echo -e "\n${BLUE}========== $* ==========${NC}"; }

# ===================== 全局变量 =====================
APP_NAME="DRPlatform"
APP_DIR="/data/${APP_NAME}"
JAR_NAME="remote-data-sync.jar"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SERVICE_FILE="/etc/systemd/system/${APP_NAME}.service"

# Nginx 配置路径（兼容宝塔面板和标准安装）
if [ -d "/www/server/nginx/conf/vhost" ]; then
    NGINX_VHOST_DIR="/www/server/nginx/conf/vhost"
    NGINX_CONF_PATH="/www/server/nginx/conf/nginx.conf"
elif [ -d "/etc/nginx/conf.d" ]; then
    NGINX_VHOST_DIR="/etc/nginx/conf.d"
    NGINX_CONF_PATH="/etc/nginx/nginx.conf"
else
    NGINX_VHOST_DIR="/etc/nginx/conf.d"
    NGINX_CONF_PATH="/etc/nginx/nginx.conf"
fi
NGINX_VHOST_FILE="${NGINX_VHOST_DIR}/${APP_NAME}.conf"

# 后端端口（默认 8090，可自定义）
BACKEND_PORT=""

# MySQL 连接信息（交互式输入，无默认值）
MYSQL_HOST=""
MYSQL_PORT=""
MYSQL_USER=""
MYSQL_PASS=""

# 同步源/目标主机信息
SYNC_SOURCE_HOST=""
SYNC_TARGET_HOST=""

# ===================== 前置检查 =====================
step "步骤 1/8: 前置检查"

if [ "$(id -u)" -ne 0 ]; then
    error "请使用 root 用户执行此脚本: sudo bash install.sh"
    exit 1
fi
info "root 权限确认 OK"

# 检测脚本目录下是否有 jar 包
JAR_PATH=""
for candidate in "${SCRIPT_DIR}/${JAR_NAME}" "${SCRIPT_DIR}/../target/${JAR_NAME}" "${SCRIPT_DIR}/bin/${JAR_NAME}"; do
    if [ -f "${candidate}" ]; then
        JAR_PATH="${candidate}"
        break
    fi
done
if [ -z "${JAR_PATH}" ]; then
    error "未找到 ${JAR_NAME}，请将 jar 包放在脚本同目录下"
    exit 1
fi
info "找到 JAR 包: ${JAR_PATH}"

# ===================== 交互式配置 =====================
step "步骤 2/8: 交互式配置"

echo ""
echo "请配置 MySQL 数据库连接信息（无默认值，必须手动输入）："
echo ""

# MySQL 主机
while true; do
    read -p "MySQL 主机地址: " MYSQL_HOST
    if [ -n "${MYSQL_HOST}" ]; then
        break
    fi
    warn "MySQL 主机地址不能为空"
done

# MySQL 端口
while true; do
    read -p "MySQL 端口: " MYSQL_PORT
    if [[ "${MYSQL_PORT}" =~ ^[0-9]+$ ]] && [ "${MYSQL_PORT}" -gt 0 ] && [ "${MYSQL_PORT}" -lt 65536 ]; then
        break
    fi
    warn "请输入有效的端口号 (1-65535)"
done

# MySQL 用户名
while true; do
    read -p "MySQL 用户名: " MYSQL_USER
    if [ -n "${MYSQL_USER}" ]; then
        break
    fi
    warn "MySQL 用户名不能为空"
done

# MySQL 密码（隐藏输入，二次确认）
while true; do
    read -s -p "MySQL 密码: " MYSQL_PASS
    echo ""
    if [ -n "${MYSQL_PASS}" ]; then
        read -s -p "确认密码: " MYSQL_PASS_CONFIRM
        echo ""
        if [ "${MYSQL_PASS}" = "${MYSQL_PASS_CONFIRM}" ]; then
            break
        fi
        warn "两次输入的密码不一致，请重新输入"
    else
        warn "MySQL 密码不能为空"
    fi
done

# 后端端口
echo ""
echo "DRPlatform 后端服务端口配置："
while true; do
    read -p "后端服务端口 (默认 8090): " BACKEND_PORT
    if [ -z "${BACKEND_PORT}" ]; then
        BACKEND_PORT="8090"
        break
    fi
    if [[ "${BACKEND_PORT}" =~ ^[0-9]+$ ]] && [ "${BACKEND_PORT}" -gt 0 ] && [ "${BACKEND_PORT}" -lt 65536 ]; then
        # 检查端口是否被占用
        if ss -tlnp | grep -q ":${BACKEND_PORT} " 2>/dev/null; then
            warn "端口 ${BACKEND_PORT} 已被占用，请选择其他端口"
        else
            break
        fi
    else
        warn "请输入有效的端口号 (1-65535)"
    fi
done
info "后端端口: ${BACKEND_PORT}"

# 同步源/目标主机
echo ""
echo "数据库同步映射配置（源主机数据 → 目标主机）："
echo "  提示: 灾备中心本机部署时，源和目标通常都指向本机 MySQL"
while true; do
    read -p "同步源主机 IP (回车=使用 MySQL 主机地址 ${MYSQL_HOST}): " SYNC_SOURCE_HOST
    SYNC_SOURCE_HOST="${SYNC_SOURCE_HOST:-${MYSQL_HOST}}"
    if [ -n "${SYNC_SOURCE_HOST}" ]; then
        break
    fi
done

while true; do
    read -p "同步目标主机 IP (回车=使用本机 MySQL ${MYSQL_HOST}): " SYNC_TARGET_HOST
    SYNC_TARGET_HOST="${SYNC_TARGET_HOST:-${MYSQL_HOST}}"
    if [ -n "${SYNC_TARGET_HOST}" ]; then
        break
    fi
done

info "配置汇总:"
echo "  MySQL: ${MYSQL_USER}@${MYSQL_HOST}:${MYSQL_PORT}"
echo "  后端端口: ${BACKEND_PORT}"
echo "  同步源: ${SYNC_SOURCE_HOST}:${MYSQL_PORT}"
echo "  同步目标: ${SYNC_TARGET_HOST}:${MYSQL_PORT}"
echo ""

read -p "确认以上配置正确? (y/N): " CONFIRM
if [ "${CONFIRM}" != "y" ] && [ "${CONFIRM}" != "Y" ]; then
    warn "用户取消安装"
    exit 0
fi

# ===================== 安装依赖 =====================
step "步骤 3/8: 安装系统依赖"

# Java
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -1 | awk -F '"' '{print $2}' | awk -F. '{print $1}')
    if [ "${JAVA_VER}" -ge 17 ] 2>/dev/null; then
        info "Java ${JAVA_VER} 已安装"
    else
        warn "Java 版本过低 (${JAVA_VER})，尝试安装 Java 17..."
        yum install -y java-17-openjdk-devel >/dev/null 2>&1 || true
    fi
else
    info "安装 Java 17..."
    yum install -y java-17-openjdk-devel >/dev/null 2>&1 || {
        warn "yum 安装 Java 17 失败，尝试从其他源安装..."
        yum install -y java >/dev/null 2>&1 || true
    }
fi
java -version 2>&1 | head -1 || { error "Java 安装失败"; exit 1; }

# Nginx
if command -v nginx >/dev/null 2>&1; then
    info "Nginx 已安装: $(nginx -v 2>&1)"
else
    info "安装 Nginx..."
    yum install -y nginx >/dev/null 2>&1 || true
fi

# MySQL 客户端
if command -v mysql >/dev/null 2>&1; then
    info "MySQL 客户端已安装"
else
    info "安装 MySQL 客户端..."
    yum install -y mariadb >/dev/null 2>&1 || yum install -y mysql >/dev/null 2>&1 || true
fi

# ===================== 测试 MySQL 连接 =====================
step "步骤 4/8: 测试 MySQL 连接"

MYSQL_CMD="mysql -h${MYSQL_HOST} -P${MYSQL_PORT} -u${MYSQL_USER} -p${MYSQL_PASS}"
if ${MYSQL_CMD} -e "SELECT 1;" >/dev/null 2>&1; then
    info "MySQL 连接成功"
else
    error "MySQL 连接失败，请检查主机/端口/用户名/密码"
    ${MYSQL_CMD} -e "SELECT 1;" 2>&1 | head -3
    exit 1
fi

# ===================== 创建数据库 + 初始化表结构 =====================
step "步骤 5/8: 创建数据库并初始化表结构"

${MYSQL_CMD} -e "CREATE DATABASE IF NOT EXISTS ${APP_NAME} DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
info "数据库 ${APP_NAME} 已就绪"

# 查找 init.sql
INIT_SQL=""
for candidate in "${SCRIPT_DIR}/init.sql" "${SCRIPT_DIR}/db/init.sql" "${SCRIPT_DIR}/../src/main/resources/db/init.sql" "${APP_DIR}/conf/db/init.sql"; do
    if [ -f "${candidate}" ]; then
        INIT_SQL="${candidate}"
        break
    fi
done

if [ -n "${INIT_SQL}" ]; then
    info "执行初始化 SQL: ${INIT_SQL}"
    ${MYSQL_CMD} < "${INIT_SQL}" 2>/dev/null && info "数据库表初始化完成" || warn "部分 SQL 可能已存在，跳过"
else
    warn "未找到 init.sql，跳过表结构初始化"
fi

# 创建 operation_log 表（如不存在）
${MYSQL_CMD} "${APP_NAME}" 2>/dev/null <<'EOF'
CREATE TABLE IF NOT EXISTS operation_log (
    ID              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    USER_ID         BIGINT       DEFAULT NULL COMMENT '操作用户ID',
    USERNAME        VARCHAR(64)  DEFAULT NULL COMMENT '操作用户名',
    OPERATION_TYPE  VARCHAR(64)  NOT NULL    COMMENT '操作类型',
    OPERATION_DESC  VARCHAR(255) DEFAULT NULL COMMENT '操作描述',
    REQUEST_URL     VARCHAR(512) DEFAULT NULL COMMENT '请求URL',
    REQUEST_METHOD  VARCHAR(10)  DEFAULT NULL COMMENT 'HTTP方法',
    REQUEST_PARAMS  TEXT         DEFAULT NULL COMMENT '请求参数(密码已脱敏)',
    RESULT_STATUS   VARCHAR(20)  DEFAULT NULL COMMENT 'SUCCESS/FAILURE',
    ERROR_MSG       TEXT         DEFAULT NULL COMMENT '错误信息',
    CLIENT_IP       VARCHAR(45)  DEFAULT NULL COMMENT '客户端IP',
    DURATION_MS     BIGINT       DEFAULT NULL COMMENT '执行耗时(ms)',
    CREATE_TIME     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    KEY IDX_USER_ID (USER_ID),
    KEY IDX_OPERATION_TYPE (OPERATION_TYPE),
    KEY IDX_CREATE_TIME (CREATE_TIME)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';
EOF
info "operation_log 表已就绪"

# 为 sync_progress 添加 user_id 列（如不存在）
${MYSQL_CMD} "${APP_NAME}" -e "ALTER TABLE sync_progress ADD COLUMN USER_ID BIGINT DEFAULT NULL COMMENT '所属用户ID' AFTER TARGET_DB_NAME;" 2>/dev/null || info "sync_progress.USER_ID 列已存在"

# ===================== 部署应用程序 =====================
step "步骤 6/8: 部署应用程序"

# 创建目录结构
mkdir -p "${APP_DIR}"/{bin,logs,conf,conf/db,frontend,savepoint}
info "目录结构已创建: ${APP_DIR}"

# 复制 jar 包
cp -f "${JAR_PATH}" "${APP_DIR}/bin/${JAR_NAME}"
info "JAR 包已部署: ${APP_DIR}/bin/${JAR_NAME}"

# 复制 init.sql 到 conf 目录（便于后续维护）
if [ -n "${INIT_SQL}" ]; then
    cp -f "${INIT_SQL}" "${APP_DIR}/conf/db/init.sql"
fi

# 复制前端静态资源
for fe_dir in "${SCRIPT_DIR}/../frontend" "${SCRIPT_DIR}/frontend" "${SCRIPT_DIR}/../remote-data-sync/frontend"; do
    if [ -d "${fe_dir}" ]; then
        cp -rf "${fe_dir}"/* "${APP_DIR}/frontend/" 2>/dev/null || true
        info "前端资源已部署"
        break
    fi
done

# 生成 application-linux.yml 配置文件
cat > "${APP_DIR}/conf/application-linux.yml" <<EOF
server:
  port: ${BACKEND_PORT}
  servlet:
    context-path: /

spring:
  application:
    name: remote-data-sync
  web:
    resources:
      cache:
        cachecontrol:
          no-store: true
          no-cache: true
          max-age: 0
  datasource:
    url: jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${APP_NAME}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false
    username: ${MYSQL_USER}
    password: ${MYSQL_PASS}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      minimum-idle: 5
      maximum-pool-size: 20
      connection-timeout: 30000
      pool-name: DRPlatform-Meta-Pool
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8
    default-property-inclusion: non_null

mybatis-plus:
  mapper-locations: classpath*:mapper/*.xml
  type-aliases-package: com.example.remotedatasync.po
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: auto
      table-prefix: ""

DRPlatform:
  standby-mode: true
  flink:
    enabled: false
    parallelism: 2
    checkpoint-interval-ms: 30000
    savepoint-path: ${APP_DIR}/savepoint
  engine:
    poll-interval-ms: 2000
    batch-size: 1000
    max-queue-size: 20000
    reconcile-interval-ms: 10000
    deviation-timeout-sec: 300
  connection:
    max-pool-size: 32
    max-global-connections: 200
  mappings:
    - source-host: ${SYNC_SOURCE_HOST}
      source-port: ${MYSQL_PORT}
      source-user: ${MYSQL_USER}
      source-password: ${MYSQL_PASS}
      target-host: ${SYNC_TARGET_HOST}
      target-port: ${MYSQL_PORT}
      target-user: ${MYSQL_USER}
      target-password: ${MYSQL_PASS}
      ignore-databases: []
      ignore-tables: []
      ignore-ddl-tables: []
      transform-rules: []

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics
  endpoint:
    health:
      show-details: always

logging:
  level:
    root: INFO
    com.example.remotedatasync: DEBUG
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [traceId=%X{traceId:-NA}] %logger{36} - %msg%n"
  file:
    name: ${APP_DIR}/logs/app.log
EOF
info "配置文件已生成: ${APP_DIR}/conf/application-linux.yml"
info "  MySQL: ${MYSQL_USER}@${MYSQL_HOST}:${MYSQL_PORT}/${APP_NAME}"
info "  后端端口: ${BACKEND_PORT}"
info "  同步源: ${SYNC_SOURCE_HOST} -> 目标: ${SYNC_TARGET_HOST}"

# ===================== 配置 systemd 服务 =====================
step "步骤 7/8: 配置 systemd 服务"

# 停止旧服务（如存在）
if systemctl is-active --quiet "${APP_NAME}" 2>/dev/null; then
    systemctl stop "${APP_NAME}" 2>/dev/null || true
    info "已停止旧服务"
fi

cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=DRPlatform Disaster Recovery Sync Service
After=network.target mysqld.service

[Service]
Type=simple
User=root
WorkingDirectory=${APP_DIR}
ExecStart=/usr/bin/java -Xms256m -Xmx512m -jar ${APP_DIR}/bin/${JAR_NAME} --spring.profiles.active=linux --spring.config.additional-location=${APP_DIR}/conf/
SuccessExitStatus=143
Restart=on-failure
RestartSec=10
StandardOutput=append:${APP_DIR}/logs/app.out
StandardError=append:${APP_DIR}/logs/app.err

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable "${APP_NAME}" >/dev/null 2>&1
info "systemd 服务已配置: ${SERVICE_FILE}"

# ===================== 配置 Nginx 反向代理 =====================
step "步骤 8/8: 配置 Nginx 并启动服务"

# 创建 Nginx vhost 配置
mkdir -p "${NGINX_VHOST_DIR}"
cat > "${NGINX_VHOST_FILE}" <<EOF
# DRPlatform 灾备同步服务 Nginx 反向代理配置
server {
    listen 80;
    server_name _;

    # 前端静态资源
    location / {
        root ${APP_DIR}/frontend;
        index index.html;
        try_files \$uri \$uri/ /index.html;
    }

    # 后端 API 反向代理
    location /sync/ {
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_connect_timeout 30s;
        proxy_read_timeout 120s;
    }

    location /auth/ {
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }

    location /actuator/ {
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
    }

    location /operation-log/ {
        proxy_pass http://127.0.0.1:${BACKEND_PORT};
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
}
EOF
info "Nginx 配置已写入: ${NGINX_VHOST_FILE}"

# 检查 Nginx 配置是否有冲突的 server_name _
# 如果有其他配置也使用 server_name _，需要修改为特定名称
CONFLICT_COUNT=$(grep -r "server_name.*_" "${NGINX_VHOST_DIR}"/*.conf 2>/dev/null | grep -v "${APP_NAME}.conf" | wc -l)
if [ "${CONFLICT_COUNT}" -gt 0 ]; then
    warn "检测到其他配置也使用了 server_name _，可能产生冲突"
    warn "建议修改 ${NGINX_VHOST_FILE} 中的 server_name 为实际域名或 IP"
fi

# 测试 Nginx 配置
if nginx -t 2>&1 | grep -q "successful"; then
    info "Nginx 配置测试通过"
    systemctl reload nginx 2>/dev/null || nginx -s reload 2>/dev/null || true
    info "Nginx 已重新加载"
else
    warn "Nginx 配置测试有警告，继续部署..."
    nginx -t 2>&1 | head -5
fi

# 启动后端服务
info "启动 DRPlatform 服务..."
systemctl restart "${APP_NAME}"
sleep 5

# 验证服务状态
if systemctl is-active --quiet "${APP_NAME}"; then
    info "DRPlatform 服务已启动 (running)"
else
    error "DRPlatform 服务启动失败"
    warn "查看日志: journalctl -u ${APP_NAME} -n 50"
    warn "或查看: tail -50 ${APP_DIR}/logs/app.out"
    exit 1
fi

# 等待应用完全启动
info "等待应用初始化..."
for i in $(seq 1 30); do
    if curl -s "http://127.0.0.1:${BACKEND_PORT}/actuator/health" 2>/dev/null | grep -q "UP"; then
        info "应用健康检查通过"
        break
    fi
    sleep 2
    if [ $i -eq 30 ]; then
        warn "健康检查超时（60秒），服务可能仍在初始化中"
    fi
done

# ===================== 部署完成 =====================
SERVER_IP=$(hostname -I | awk '{print $1}')

echo ""
echo "============================================================"
echo -e "${GREEN}  DRPlatform 灾备同步服务部署完成！${NC}"
echo "============================================================"
echo ""
echo "  访问地址:"
echo "    前端页面:  http://${SERVER_IP}/"
echo "    后端 API:  http://${SERVER_IP}:${BACKEND_PORT}/actuator/health"
echo "    默认账号:  admin / admin123"
echo ""
echo "  配置文件:    ${APP_DIR}/conf/application-linux.yml"
echo "  日志目录:    ${APP_DIR}/logs/"
echo "  数据目录:    ${APP_DIR}/"
echo ""
echo "  常用命令:"
echo "    启动:  systemctl start ${APP_NAME}"
echo "    停止:  systemctl stop ${APP_NAME}"
echo "    重启:  systemctl restart ${APP_NAME}"
echo "    状态:  systemctl status ${APP_NAME}"
echo "    日志:  journalctl -u ${APP_NAME} -f"
echo "           tail -f ${APP_DIR}/logs/app.log"
echo ""
echo "  卸载:"
echo "    systemctl stop ${APP_NAME} && systemctl disable ${APP_NAME}"
echo "    rm -f ${SERVICE_FILE} ${NGINX_VHOST_FILE}"
echo "    rm -rf ${APP_DIR}"
echo "    systemctl daemon-reload && nginx -s reload"
echo ""
echo "============================================================"
