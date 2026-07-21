#!/bin/bash
# 启动 DRPlatform 服务 (CentOS7)
set -e
APP_DIR="/data/DRPlatform"
JAR="${APP_DIR}/bin/remote-data-sync.jar"
if [ -f /etc/systemd/system/DRPlatform.service ]; then
  systemctl start DRPlatform
  echo "已通过 systemd 启动 DRPlatform"
else
  nohup java -jar "${JAR}" --spring.profiles.active=linux \
    > "${APP_DIR}/logs/app.out" 2>&1 &
  echo "已后台启动 (pid $!), 日志见 ${APP_DIR}/logs/app.out"
fi
