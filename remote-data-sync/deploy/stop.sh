#!/bin/bash
# 停止 DRPlatform 服务 (CentOS7)
set -e
if [ -f /etc/systemd/system/DRPlatform.service ]; then
  systemctl stop DRPlatform && echo "已通过 systemd 停止 DRPlatform" || echo "停止失败或服务未运行"
else
  pkill -f "remote-data-sync.jar" && echo "已停止后台进程" || echo "未找到运行中的进程"
fi
