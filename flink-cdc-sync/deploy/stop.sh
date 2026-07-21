#!/bin/bash
# 停止 GeoDRSync 服务 (CentOS7)
set -e
if [ -f /etc/systemd/system/geodrsync.service ]; then
  systemctl stop geodrsync && echo "已通过 systemd 停止 geodrsync" || echo "停止失败或服务未运行"
else
  pkill -f "flink-cdc-sync.jar" && echo "已停止后台进程" || echo "未找到运行中的进程"
fi
