#!/bin/bash
# =============================================================================
# DRPlatform 守护进程 (watchdog) —— 灾备同步服务自愈 supervisor
#
# 职责:
#   1. 持续监听后端应用(remote-data-sync.jar)进程与端口健康；
#   2. 应用宕机(进程退出)或假死(端口/health 无响应)时自动拉起；
#   3. 收到 SIGTERM/SIGINT(来自 systemctl stop 或 stop.sh)时，干净地停掉
#      应用并以退出码 0 退出 —— 因此"手动停止"绝不会被自动拉起；
#   4. 自身被异常杀死(非 0 退出)时，交由 systemd Restart=on-failure 重新拉起。
#
# 设计要点(满足"手动停止的不要自动拉起来"):
#   - 真正决定是否"存活"的是本守护进程。stop.sh 会先终止本进程；
#   - 本进程在收到停止信号后主动杀掉应用并 exit 0，systemd 视为主动停止，
#     不会触发 Restart；
#   - 应用若自行崩溃，本进程仍在运行，会在下一轮循环拉起它。
# =============================================================================

# ----------------------------- 路径自定位 -----------------------------
# 本脚本被安装到 ${APP_DIR}/bin/watchdog.sh，据此推导 APP_DIR。
SCRIPT_PATH="$(readlink -f "$0" 2>/dev/null || echo "$0")"
SCRIPT_DIR="$(cd "$(dirname "${SCRIPT_PATH}")" && pwd)"
APP_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

APP_NAME="DRPlatform"
JAR_NAME="remote-data-sync.jar"
JAR="${APP_DIR}/bin/${JAR_NAME}"
RUN_DIR="${APP_DIR}/run"
LOG_DIR="${APP_DIR}/logs"
APP_PIDFILE="${RUN_DIR}/app.pid"
WATCHDOG_PIDFILE="${RUN_DIR}/watchdog.pid"
APP_OUT="${LOG_DIR}/app.out"
WATCHDOG_OUT="${LOG_DIR}/watchdog.out"

# JVM / Spring 参数(与历史部署保持一致)
JAVA_OPTS="${JAVA_OPTS:-"-Xms256m -Xmx512m"}"
SPRING_OPTS="--spring.profiles.active=linux --spring.config.additional-location=${APP_DIR}/conf/"
PORT="${DRPLATFORM_PORT:-8899}"

# 健康检查参数
HEALTH_URL="http://127.0.0.1:${PORT}/"   # 仅做 TCP 连通性探测(应用未暴露 actuator 端点)
HEALTH_GRACE=120        # 应用启动后多少秒才开始做 HTTP 健康检查(避免启动期误判)
POLL_INTERVAL=5         # 主循环轮询间隔(秒)
HEALTH_TIMEOUT=5        # 单次 health 探测超时(秒)

# ----------------------------- 工具函数 -----------------------------
log() { echo "$(date '+%Y-%m-%d %H:%M:%S') [watchdog] $*"; }

mkdir -p "${RUN_DIR}" "${LOG_DIR}"

# 判断应用是否"真正在运行":
#   优先用 pgrep 精确匹配 java 命令行中的 jar 名(防止误判同名进程)；
#   再兜底: pidfile 中记录的进程仍存活。
is_app_running() {
    local pids
    pids="$(pgrep -f 'remote-data-sync\.jar' 2>/dev/null || true)"
    if [ -n "${pids}" ]; then
        # 取首个匹配 pid 写入 pidfile, 便于精准停止
        echo "${pids%%[[:space:]]*}" > "${APP_PIDFILE}"
        return 0
    fi
    if [ -f "${APP_PIDFILE}" ]; then
        local pid
        pid="$(cat "${APP_PIDFILE}" 2>/dev/null)"
        [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null && return 0
    fi
    return 1
}

# 端口是否被占用(用于启动前等待旧实例释放, 避免 BindException 重启风暴)
port_in_use() {
    command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":${PORT} " && return 0
    command -v curl >/dev/null 2>&1 && curl -s -o /dev/null --max-time 2 "${HEALTH_URL}" >/dev/null 2>&1 && return 0
    return 1
}

# 启动应用(若已有实例则跳过)
start_app() {
    if is_app_running; then
        log "应用已在运行，跳过启动"
        return 0
    fi
    # 等待端口释放, 避免旧实例尚未松绑导致 BindException 重启风暴
    local w=0
    while port_in_use; do
        if [ "${w}" -ge 30 ]; then
            log "等待端口 ${PORT} 释放超时(30s), 仍尝试启动"
            break
        fi
        sleep 1; w=$((w+1))
    done
    log "启动应用: java ${JAVA_OPTS} -jar ${JAR} ${SPRING_OPTS}"
    nohup java ${JAVA_OPTS} -jar "${JAR}" ${SPRING_OPTS} >> "${APP_OUT}" 2>&1 &
    local pid=$!
    echo "${pid}" > "${APP_PIDFILE}"
    log "应用已启动 pid=${pid}"
}

# 停止应用(优雅 SIGTERM, 超时后 SIGKILL)
stop_app() {
    if [ -f "${APP_PIDFILE}" ]; then
        local pid
        pid="$(cat "${APP_PIDFILE}" 2>/dev/null)"
        if [ -n "${pid}" ] && kill -0 "${pid}" 2>/dev/null; then
            log "停止应用 pid=${pid}"
            kill "${pid}" 2>/dev/null
            local i
            for i in $(seq 1 20); do
                kill -0 "${pid}" 2>/dev/null || break
                sleep 1
            done
            kill -9 "${pid}" 2>/dev/null || true
        fi
        rm -f "${APP_PIDFILE}"
    fi
    # 兜底清理可能残留的孤儿进程(使用 pgrep, 兼容性优于 pkill)
    local p
    for p in $(pgrep -f 'remote-data-sync\.jar' 2>/dev/null); do
        kill "${p}" 2>/dev/null || true
    done
}

# HTTP 健康检查(仅在 grace 期之后生效)
# 仅做 TCP 连通性探测: 只要能与端口建立连接(应用进程在监听并响应), 即视为存活。
# 不校验业务状态码(本应用未暴露 /actuator, 根路径返回 404 也说明服务在响应)。
check_health() {
    local now elapsed
    now="$(date +%s)"
    elapsed=$(( now - last_start_ts ))
    if [ "${elapsed}" -lt "${HEALTH_GRACE}" ]; then
        return 0   # 启动宽限期内，视为健康，避免误杀
    fi
    if command -v curl >/dev/null 2>&1; then
        if curl -s -o /dev/null --max-time "${HEALTH_TIMEOUT}" "${HEALTH_URL}" >/dev/null 2>&1; then
            return 0
        fi
        return 1
    fi
    command -v ss >/dev/null 2>&1 && ss -ltn 2>/dev/null | grep -q ":${PORT} " && return 0
    return 1
}

# 收到停止信号: 干净地停掉应用并以 0 退出(手动停止 -> 不自动拉起)
cleanup() {
    log "收到停止信号，正在退出(手动停止，不自动拉起)..."
    stop_app
    rm -f "${WATCHDOG_PIDFILE}"
    exit 0
}
trap cleanup TERM INT

# ----------------------------- 主循环 -----------------------------
echo "$$" > "${WATCHDOG_PIDFILE}"
last_start_ts=0

log "守护进程启动, APP_DIR=${APP_DIR}, 监听端口=${PORT}"

while true; do
    if is_app_running; then
        if check_health; then
            sleep "${POLL_INTERVAL}"
        else
            log "健康检查失败(端口 ${PORT} 无响应)，重启应用..."
            stop_app
            sleep 2
            start_app
            last_start_ts="$(date +%s)"
            sleep "${POLL_INTERVAL}"
        fi
    else
        log "检测到应用未运行，自动拉起..."
        start_app
        last_start_ts="$(date +%s)"
        sleep "${POLL_INTERVAL}"
    fi
done
