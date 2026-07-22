#!/bin/bash
# =============================================================================
# DRPlatform 一键停止脚本 (Linux)
# 用法:  sudo bash stop.sh
#
# 关键语义: "手动停止的不要自动拉起来"
#   - 先终止守护进程(watchdog)，使其失去"自动拉起"的能力；
#   - 守护进程在收到 SIGTERM 时会主动停掉应用并以退出码 0 退出，
#     systemd 视为主动停止, 不会触发 Restart；
#   - 因此手动停止后, 应用与守护进程都会保持停止, 直到下次手动 start
#     或系统重启(开机自启会重新拉起)。
# =============================================================================
set -e

APP_NAME="DRPlatform"
APP_DIR="/data/${APP_NAME}"
SERVICE="/etc/systemd/system/${APP_NAME}.service"
RUN_DIR="${APP_DIR}/run"

SYSTEMD=0
if command -v systemctl >/dev/null 2>&1 && [ -f "${SERVICE}" ]; then
    SYSTEMD=1
fi

echo "[INFO] 正在停止 ${APP_NAME} (手动停止, 不会自动拉起)..."

if [ "${SYSTEMD}" -eq 1 ]; then
    # 让 systemd 把 SIGTERM 发给守护进程, 由其干净退出(退出码 0 -> 不重启)
    systemctl stop "${APP_NAME}" && echo "[OK] 已通过 systemd 停止" || echo "[WARN] 停止失败或服务未运行"
else
    # 兜底: 直接杀掉守护进程(先), 再杀应用, 确保不会立即被拉起
    if [ -f "${RUN_DIR}/watchdog.pid" ]; then
        wpid="$(cat "${RUN_DIR}/watchdog.pid" 2>/dev/null)"
        [ -n "${wpid}" ] && kill "${wpid}" 2>/dev/null || true
    fi
    for p in $(pgrep -f "watchdog.sh" 2>/dev/null); do kill "${p}" 2>/dev/null || true; done
    sleep 1
    for p in $(pgrep -f "remote-data-sync\.jar" 2>/dev/null); do kill "${p}" 2>/dev/null || true; done
    rm -f "${RUN_DIR}/app.pid" "${RUN_DIR}/watchdog.pid"
    echo "[OK] 已停止守护进程与应用"
fi

# ----------------------------- 状态回显 -----------------------------
if [ "${SYSTEMD}" -eq 1 ]; then
    if systemctl is-active --quiet "${APP_NAME}" 2>/dev/null; then
        echo "[WARN] 服务似乎仍在运行, 请检查: journalctl -u ${APP_NAME}"
    else
        echo "[OK] 服务已停止"
    fi
else
    if pgrep -f "remote-data-sync.jar" >/dev/null 2>&1 || pgrep -f "watchdog.sh" >/dev/null 2>&1; then
        echo "[WARN] 仍有相关进程存活, 请手动检查"
    else
        echo "[OK] 服务已停止"
    fi
fi

echo "---------------------------------------------------"
echo " 如需重新启动: bash ${APP_DIR}/bin/start.sh"
echo "---------------------------------------------------"
