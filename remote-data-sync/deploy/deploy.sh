#!/bin/bash
# =============================================================================
# DRPlatform 灾备同步服务 - CentOS7 一键部署脚本
# 用法:  sudo bash deploy.sh
# 说明:
#   - 本脚本在 灾备中心(Linux) 执行，部署后端 jar + 前端 + Nginx 反代。
#   - 数据库信息固定为 192.168.88.88:3306 root/123456 (与设计文档一致)。
#   - 内嵌同步引擎已在 jar 内，无需外部 Flink 集群即可运行。
# =============================================================================
set -euo pipefail

APP_NAME="DRPlatform"
APP_DIR="/data/${APP_NAME}"
JAR_NAME="remote-data-sync.jar"
SRC_JAR="../remote-data-sync/target/${JAR_NAME}"
DB_HOST="192.168.88.88"
DB_PORT="3306"
DB_USER="root"
DB_PASS="123456"
NGINX_CONF="/etc/nginx/conf.d/${APP_NAME}.conf"
SERVICE_FILE="/etc/systemd/system/${APP_NAME}.service"

echo "==> [1/7] 检查运行环境(需 root)"
[ "$(id -u)" -eq 0 ] || { echo "请使用 root 执行部署"; exit 1; }

echo "==> [2/7] 安装依赖 (java17 / nginx / mysql client)"
if ! command -v java >/dev/null 2>&1; then
  yum install -y java-17-openjdk-devel >/dev/null 2>&1 || yum install -y java-11-openjdk-devel >/dev/null 2>&1 || true
fi
command -v nginx >/dev/null 2>&1 || yum install -y nginx >/dev/null 2>&1 || true
command -v mysql >/dev/null 2>&1 || yum install -y mariadb >/dev/null 2>&1 || true
java -version 2>&1 | head -1

echo "==> [3/7] 创建目录并放置程序"
mkdir -p "${APP_DIR}"/{bin,logs,conf,frontend,savepoint}
[ -f "${SRC_JAR}" ] || { echo "未找到 ${SRC_JAR}，请先 mvn clean package"; exit 1; }
cp -f "${SRC_JAR}" "${APP_DIR}/bin/${JAR_NAME}"
# 前端静态资源
[ -d "../remote-data-sync/frontend" ] && cp -rf ../remote-data-sync/frontend/* "${APP_DIR}/frontend/"
# 初始化 SQL
[ -d "${APP_DIR}/conf/db" ] || mkdir -p "${APP_DIR}/conf/db"
cp -f ../remote-data-sync/src/main/resources/db/init.sql "${APP_DIR}/conf/db/init.sql"

echo "==> [4/7] 初始化数据库 (${DB_HOST}:${DB_PORT})"
mysql -h"${DB_HOST}" -P"${DB_PORT}" -u"${DB_USER}" -p"${DB_PASS}" < "${APP_DIR}/conf/db/init.sql" \
  && echo "数据库初始化完成" || echo "数据库初始化跳过(可能已存在)"

echo "==> [5/7] 部署控制脚本与 systemd 守护进程单元"
# 复制一键启停 / 守护进程 / 开机自启脚本到 bin 目录
SCRIPT_SRC="$(cd "$(dirname "$0")" && pwd)"
for f in watchdog.sh start.sh stop.sh boot.sh; do
  if [ -f "${SCRIPT_SRC}/${f}" ]; then
    cp -f "${SCRIPT_SRC}/${f}" "${APP_DIR}/bin/${f}"
    chmod +x "${APP_DIR}/bin/${f}"
  fi
done
echo "控制脚本已部署: ${APP_DIR}/bin/{watchdog,start,stop,boot}.sh"

cat > "${SERVICE_FILE}" <<EOF
[Unit]
Description=DRPlatform Disaster Recovery Sync Service (watchdog supervised)
After=network.target mysqld.service mariadb.service
Wants=mysqld.service

[Service]
Type=simple
User=root
WorkingDirectory=${APP_DIR}
# 由守护进程(watchdog)负责拉起并看护应用; 收到 SIGTERM 干净退出(退出码 0)
ExecStart=${APP_DIR}/bin/watchdog.sh
Restart=on-failure
RestartSec=5
SuccessExitStatus=0 143
StandardOutput=append:${APP_DIR}/logs/watchdog.out
StandardError=append:${APP_DIR}/logs/watchdog.out

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable "${APP_NAME}"

echo "==> [6/7] 写入 Nginx 反代配置"
cat > "${NGINX_CONF}" <<EOF
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
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
    }
    location /actuator/ {
        proxy_pass http://127.0.0.1:8080;
    }
}
EOF
systemctl enable nginx >/dev/null 2>&1 || true

echo "==> [7/7] 启动服务"
systemctl restart nginx >/dev/null 2>&1 || true
systemctl restart "${APP_NAME}" || echo "服务启动失败，请查看 journalctl -u ${APP_NAME}"
sleep 5
echo "---------------------------------------------------"
echo "部署完成！"
echo "  后端 API : http://<本机IP>:8080/sync/status"
echo "  前端页面 : http://<本机IP>/"
echo "  日志     : journalctl -u ${APP_NAME} -f"
echo "  启停     : bash ${APP_DIR}/bin/start.sh | stop.sh"
echo "---------------------------------------------------"
