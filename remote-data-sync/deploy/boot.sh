#!/bin/bash
# =============================================================================
# DRPlatform 开机自启管理脚本 (Linux)
# 用法:
#   sudo bash boot.sh enable    # 开启开机自启
#   sudo bash boot.sh disable   # 关闭开机自启(仅影响重启后的自动启动, 不停止当前运行)
#   sudo bash boot.sh status    # 查看当前开机自启状态
#
# 说明: 开机自启=在系统启动时运行守护进程(watchdog)，由其拉起应用。
#       关闭开机自启后, 当前正在运行的服务不受影响, 仅重启后不会自动启动。
# =============================================================================
set -e

APP_NAME="DRPlatform"
APP_DIR="/data/${APP_NAME}"
WATCHDOG="${APP_DIR}/bin/watchdog.sh"
SERVICE="/etc/systemd/system/${APP_NAME}.service"

ACTION="${1:-status}"

SYSTEMD=0
if command -v systemctl >/dev/null 2>&1 && [ -f "${SERVICE}" ]; then
    SYSTEMD=1
fi

case "${ACTION}" in
    enable)
        if [ "${SYSTEMD}" -eq 1 ]; then
            systemctl enable "${APP_NAME}" >/dev/null 2>&1 && echo "[OK] 已开启开机自启 (systemd)" || echo "[ERROR] 开启失败"
        else
            if crontab -l 2>/dev/null | grep -q "${WATCHDOG}"; then
                echo "[INFO] 已通过 crontab @reboot 开启"
            else
                ( crontab -l 2>/dev/null; echo "@reboot /bin/bash ${WATCHDOG} >> ${APP_DIR}/logs/watchdog.out 2>&1 &" ) | crontab - 2>/dev/null \
                    && echo "[OK] 已开启开机自启 (crontab @reboot)" || echo "[ERROR] 开启失败"
            fi
        fi
        ;;
    disable)
        if [ "${SYSTEMD}" -eq 1 ]; then
            systemctl disable "${APP_NAME}" >/dev/null 2>&1 && echo "[OK] 已关闭开机自启 (systemd)" || echo "[WARN] 关闭失败或未启用"
        else
            if crontab -l 2>/dev/null | grep -q "${WATCHDOG}"; then
                crontab -l 2>/dev/null | grep -v "${WATCHDOG}" | crontab - 2>/dev/null \
                    && echo "[OK] 已关闭开机自启 (crontab)" || echo "[WARN] 关闭失败"
            else
                echo "[INFO] 当前未配置开机自启"
            fi
        fi
        echo "[INFO] 当前运行中的服务不受影响, 仅重启后不再自动启动"
        ;;
    status)
        if [ "${SYSTEMD}" -eq 1 ]; then
            if systemctl is-enabled --quiet "${APP_NAME}" 2>/dev/null; then
                echo "[INFO] 开机自启: 已开启 (systemd)"
            else
                echo "[INFO] 开机自启: 未开启 (systemd)"
            fi
        else
            if crontab -l 2>/dev/null | grep -q "${WATCHDOG}"; then
                echo "[INFO] 开机自启: 已开启 (crontab @reboot)"
            else
                echo "[INFO] 开机自启: 未开启"
            fi
        fi
        ;;
    *)
        echo "用法: bash boot.sh {enable|disable|status}"
        exit 1
        ;;
esac
