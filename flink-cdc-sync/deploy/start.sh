#!/bin/bash
# 启动 GeoDRSync 服务 (CentOS7)
set -e
APP_DIR="/data/geodrsync"
JAR="${APP_DIR}/bin/flink-cdc-sync.jar"
if [ -f /etc/systemd/system/geodrsync.service ]; then
  systemctl start geodrsync
  echo "已通过 systemd 启动 geodrsync"
else
  nohup java -jar "${JAR}" --spring.profiles.active=linux \
    > "${APP_DIR}/logs/app.out" 2>&1 &
  echo "已后台启动 (pid $!), 日志见 ${APP_DIR}/logs/app.out"
fi
