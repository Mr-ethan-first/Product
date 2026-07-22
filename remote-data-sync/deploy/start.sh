#!/bin/bash
# =============================================================================
# DRPlatform 一键启动脚本 (Linux)
# 用法:  sudo bash start.sh          # 开启开机自启 + 启动服务
#        sudo bash start.sh --no-boot # 仅本次启动, 不改动开机自启
#
# 说明:
#   - 优先使用 systemd 管理服务(DRPlatform.service -> 守护进程 watchdog.sh)；
#   - 无 systemd 环境(如 WSL / 容器)自动降级为 nohup 后台启动守护进程；
#   - 启动即开启"开机自启"(除非显式 --no-boot)。
# =============================================================================
set -e

APP_NAME="DRPlatform"
APP_DIR="/data/${APP_NAME}"
WATCHDOG="${APP_DIR}/bin/watchdog.sh"
SERVICE="/etc/systemd/system/${APP_NAME}.service"

NO_BOOT=0
[ "${1:-}" = "--no-boot" ] && NO_BOOT=1

SYSTEMD=0
if command -v systemctl >/dev/null 2>&1 && [ -f "${SERVICE}" ]; then
    SYSTEMD=1
fi

# ----------------------------- 开机自启 -----------------------------
if [ "${NO_BOOT}" -eq 0 ]; then
    if [ "${SYSTEMD}" -eq 1 ]; then
        systemctl enable "${APP_NAME}" >/dev/null 2>&1 && echo "[OK] 已开启开机自启 (systemd)" || echo "[WARN] 开启开机自启失败"
    else
        # 非 systemd: 使用 crontab @reboot 兜底
        if crontab -l 2>/dev/null | grep -q "${WATCHDOG}"; then
            echo "[INFO] 开机自启已通过 crontab @reboot 配置"
        else
            ( crontab -l 2>/dev/null; echo "@reboot /bin/bash ${WATCHDOG} >> ${APP_DIR}/logs/watchdog.out 2>&1 &" ) | crontab - 2>/dev/null \
                && echo "[OK] 已开启开机自启 (crontab @reboot)" || echo "[WARN] 配置 crontab 开机自启失败"
        fi
    fi
else
    echo "[INFO] 跳过开机自启配置 (--no-boot)"
fi

# ----------------------------- 启动服务 -----------------------------
if [ "${SYSTEMD}" -eq 1 ]; then
    if systemctl is-active --quiet "${APP_NAME}" 2>/dev/null; then
        echo "[INFO] 服务已在运行, 执行 restart 以确保最新配置生效"
        systemctl restart "${APP_NAME}"
    else
        systemctl start "${APP_NAME}"
    fi
    sleep 3
    if systemctl is-active --quiet "${APP_NAME}" 2>/dev/null; then
        echo "[OK] 已通过 systemd 启动 (守护进程 + 应用)"
    else
        echo "[ERROR] 启动失败, 请查看: journalctl -u ${APP_NAME} -n 50"
        exit 1
    fi
else
    if pgrep -f "watchdog.sh" >/dev/null 2>&1; then
        echo "[INFO] 守护进程已在运行, 无需重复启动"
    else
        mkdir -p "${APP_DIR}/logs"
        nohup /bin/bash "${WATCHDOG}" >> "${APP_DIR}/logs/watchdog.out" 2>&1 &
        echo "[OK] 已后台启动守护进程 (pid $!)"
        sleep 3
    fi
fi

# ----------------------------- 状态回显 -----------------------------
echo "---------------------------------------------------"
echo " 服务名   : ${APP_NAME}"
echo " 部署目录 : ${APP_DIR}"
echo " 控制方式 : $([ "${SYSTEMD}" -eq 1 ] && echo systemd || echo nohup 守护进程)"
echo " 一键停止 : bash ${APP_DIR}/bin/stop.sh"
echo " 实时日志 : tail -f ${APP_DIR}/logs/app.out"
echo "---------------------------------------------------"
