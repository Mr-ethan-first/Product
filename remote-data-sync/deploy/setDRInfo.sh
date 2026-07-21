#!/bin/bash
# =============================================================================
# setDRInfo.sh - 设置/切换 生产中心 与 灾备中心 数据库 IP (CentOS7 运维脚本)
# 用法:
#   bash setDRInfo.sh                      # 交互式输入
#   bash setDRInfo.sh 10.0.0.1 10.0.0.2    # 生产中心IP 灾备中心IP
# 说明: 修改部署目录中的 application.yml(linux profile 映射)，并重启服务生效。
# =============================================================================
set -euo pipefail
APP_DIR="/data/DRPlatform"
YML="${APP_DIR}/bin/application.yml"
[ -f "${YML}" ] || YML="$(dirname "$0")/../remote-data-sync/src/main/resources/application.yml"

PROD_IP="${1:-}"
STANDBY_IP="${2:-}"

if [ -z "${PROD_IP}" ]; then read -r -p "请输入生产中心(源)数据库 IP: " PROD_IP; fi
if [ -z "${STANDBY_IP}" ]; then read -r -p "请输入灾备中心(目标)数据库 IP: " STANDBY_IP; fi

echo "将生产中心设为 ${PROD_IP}，灾备中心设为 ${STANDBY_IP}"

# 仅替换 linux profile 段(从 'on-profile: linux' 到文件末尾)内的 source-host / target-host
TMP="$(mktemp)"
awk -v prod="${PROD_IP}" -v stdby="${STANDBY_IP}" '
  /on-profile: linux/ { in_linux=1 }
  in_linux && /^[[:space:]]*source-host:/ { sub(/:.*/, ": " prod); print; next }
  in_linux && /^[[:space:]]*target-host:/ { sub(/:.*/, ": " stdby); print; next }
  { print }
' "${YML}" > "${TMP}"
mv "${TMP}" "${YML}"

echo "已更新 ${YML}"
if [ -f /etc/systemd/system/DRPlatform.service ]; then
  systemctl restart DRPlatform && echo "服务已重启" || echo "重启失败，请检查 journalctl -u DRPlatform"
fi
