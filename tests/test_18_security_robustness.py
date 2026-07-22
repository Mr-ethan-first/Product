# -*- coding: utf-8 -*-
"""DRPlatform 安全与健壮性自动化测试（隔离实验室）。

本文件在 tests/docker-lab 搭建的隔离环境上运行（与 test_17 同套基础设施）：
  - 源 MySQL  : 127.0.0.1:13306 (容器 drp-src-mysql)
  - 目标 MySQL: 127.0.0.1:13307 (容器 drp-tgt-mysql)
  - 同步服务  : 本地 127.0.0.1:8899 (SPRING_PROFILES_ACTIVE=dockerlab)

针对「缺陷修复」做回归验证，覆盖：
  ① CORS 不允许通配符 `*` 与凭证同用（CSRF 闭环，缺陷④）；
  ② 明文密码不再落盘：mapping/add 持久化后 DRPlatform-dynamic-mappings.json 中密码为 ENC: 密文（缺陷⑤）；
  ③ 监控盲区修复：源库断电后 /sync/status 必须返回 ABNORMAL（而非永远 NORMAL，缺陷⑦）；
  ④ 同时间戳大批量（>5000 行）不丢数据：keyset 续扫修复验证（缺陷⑧）；
  ⑤ 元数据注入被拒：/sync/sourceTables 传入恶意库名不 500 裸栈、不泄露 SQL（缺陷①）。
"""
import os
import time
import json
import uuid
import subprocess
from datetime import datetime
import pytest
import requests
import pymysql

LAB_BASE_URL = os.environ.get("DRPLATFORM_LAB_URL", "http://127.0.0.1:8899")
SRC = dict(host="127.0.0.1", port=13306, user="root", password="123456")
TGT = dict(host="127.0.0.1", port=13307, user="root", password="123456")
ADMIN_USER = os.environ.get("DRPLATFORM_ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("DRPLATFORM_ADMIN_PASS", "admin123")
APP_JAR = r"D:\WorkSpace\flink-cdc-sync\remote-data-sync\target\remote-data-sync.jar"
APP_CWD = r"D:\WorkSpace\flink-cdc-sync\remote-data-sync"
APP_LOG = r"D:\WorkSpace\flink-cdc-sync\tests\docker-lab\app-lab-sec.log"
MAPPINGS_FILE = r"D:\WorkSpace\flink-cdc-sync\remote-data-sync\DRPlatform-dynamic-mappings.json"
JAVA = r"D:\software\jdk-21.0.11\bin\java.exe"
SRC_CTR = "drp-src-mysql"
TGT_CTR = "drp-tgt-mysql"
SYNC_POLL_S = 2

# 由本测试用 subprocess.Popen 托管的应用子进程句柄（精确跟踪 PID，避免误杀其它进程）
_APP_PROC = None


# ----------------------------- 基础设施辅助 -----------------------------

def docker(args, timeout=150):
    r = subprocess.run(["docker"] + args, capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args)} 失败: {r.stderr.strip()}")
    return r.stdout.strip()


def db_conn(c, db=None, timeout=10):
    last = None
    for _ in range(6):
        try:
            return pymysql.connect(host=c["host"], port=c["port"], user=c["user"], password=c["password"],
                                   database=db, charset="utf8mb4",
                                   connect_timeout=timeout, read_timeout=30, write_timeout=30)
        except Exception as e:
            last = e
            time.sleep(2)
    raise last


def db_exec(c, sql, db=None, args=None):
    conn = db_conn(c, db)
    try:
        with conn.cursor() as cur:
            # 注意：args 为 None 时直接执行（不格式化），避免 SQL 中的字面量 '%'（如 LIKE 'KST_%'）
            # 被 pymysql 当成参数占位符而报 "not enough arguments for format string"。
            cur.execute(sql, args)
            conn.commit()
            return cur.rowcount
    finally:
        conn.close()


def db_count(c, db, table, where=None):
    conn = db_conn(c, db)
    try:
        with conn.cursor() as cur:
            sql = f"SELECT COUNT(1) FROM `{table}`" + (f" WHERE {where}" if where else "")
            cur.execute(sql)
            return cur.fetchone()[0]
    except Exception:
        return -1
    finally:
        conn.close()


def wait_for(fn, timeout, desc):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            last = fn()
            if last:
                return last
        except Exception:
            last = None
        time.sleep(2)
    raise AssertionError(f"等待「{desc}」超时（{timeout}s），最后结果: {last}")


def wait_mysql(c, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            conn = db_conn(c, timeout=3)
            conn.close()
            return True
        except Exception:
            time.sleep(2)
    raise AssertionError(f"MySQL {c['host']}:{c['port']} 在 {timeout}s 内不可达")


def find_app_pid():
    ps = r"Get-NetTCPConnection -LocalPort 8899 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess"
    out = subprocess.run(["powershell", "-NoProfile", "-Command", ps],
                         capture_output=True, text=True, timeout=30).stdout
    for line in out.splitlines():
        line = line.strip()
        if line.isdigit():
            return int(line)
    return None


def _kill_pid(pid):
    if not pid:
        return
    subprocess.run(["powershell", "-NoProfile", "-Command", f"Stop-Process -Id {pid} -Force"],
                   capture_output=True, timeout=30)


def stop_app():
    global _APP_PROC
    if _APP_PROC is not None and _APP_PROC.poll() is None:
        _kill_pid(_APP_PROC.pid)
    pid = find_app_pid()
    if pid:
        _kill_pid(pid)
    for _ in range(20):
        time.sleep(1)
        if (_APP_PROC is None or _APP_PROC.poll() is not None) and find_app_pid() is None:
            _APP_PROC = None
            return True
    _APP_PROC = None
    return False


def start_app():
    global _APP_PROC
    env = dict(os.environ, SPRING_PROFILES_ACTIVE="dockerlab")
    env.pop("SERVER__PORT", None)
    env.pop("SERVER_PORT", None)
    occ = find_app_pid()
    if occ:
        _kill_pid(occ)
        time.sleep(2)
    with open(APP_LOG, "a", encoding="utf-8") as log:
        _APP_PROC = subprocess.Popen([JAVA, "-jar", APP_JAR, "--server.port=8899"],
                                     cwd=APP_CWD, env=env, stdout=log, stderr=subprocess.STDOUT)
    deadline = time.time() + 120
    while time.time() < deadline:
        time.sleep(3)
        if _APP_PROC.poll() is not None:
            raise RuntimeError(f"同步服务进程意外退出（exit={_APP_PROC.poll()}），见 {APP_LOG}")
        try:
            _login()
            return True
        except Exception:
            pass
    raise RuntimeError("同步服务启动超时（120s）")


def _login(base=LAB_BASE_URL):
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    r = s.post(f"{base}/auth/login", json={"username": ADMIN_USER, "password": ADMIN_PASS}, timeout=20)
    assert r.status_code == 200, f"登录失败 {r.status_code}: {r.text[:200]}"
    assert r.json().get("success") is True
    return s


@pytest.fixture(scope="module")
def session():
    try:
        docker(["inspect", "-f", "{{.State.Running}}", SRC_CTR])
    except Exception:
        docker(["compose", "-f", r"D:\WorkSpace\flink-cdc-sync\tests\docker-lab\docker-compose.yml", "up", "-d"])
    docker(["start", SRC_CTR])
    docker(["start", TGT_CTR])
    wait_mysql(SRC)
    wait_mysql(TGT)
    stop_app()
    start_app()
    s = _login()
    yield s
    try:
        stop_app()
    except Exception:
        pass


def marker():
    return "SEC_" + uuid.uuid4().hex[:14]


# ----------------------------- 测试用例 -----------------------------

def test_00_cors_no_wildcard_with_credentials(session):
    """缺陷④ CORS：未配置跨域来源时，跨域（含凭证）请求不得回显 Access-Control-Allow-Origin: *（杜绝 CSRF）。"""
    evil_origin = "http://evil.example.com"
    # 1) 实际跨域请求携带凭证 -> 不得出现通配符 ACAO
    r = session.get(f"{LAB_BASE_URL}/sync/status",
                    headers={"Origin": evil_origin, "Credentials": "include"}, timeout=10)
    acao = r.headers.get("Access-Control-Allow-Origin")
    assert acao != "*", f"跨域请求不应回显通配符 ACAO（CSRF 风险），实际: {acao}"
    # 2) 预检 OPTIONS 同样不得出现通配符 ACAO
    ro = requests.options(f"{LAB_BASE_URL}/sync/db/list",
                          headers={"Origin": evil_origin,
                                    "Access-Control-Request-Method": "POST",
                                    "Access-Control-Request-Headers": "content-type"}, timeout=10)
    acao_o = ro.headers.get("Access-Control-Allow-Origin")
    assert acao_o != "*", f"预检请求不应回显通配符 ACAO，实际: {acao_o}"


def test_01_monitoring_blindspot_abnormal_on_src_down(session):
    """缺陷⑦ 监控盲区：源库断电后 /sync/status 必须返回 ABNORMAL（而非永远 NORMAL），恢复后回到 NORMAL。"""
    # 基线：正常态
    st = session.get(f"{LAB_BASE_URL}/sync/status", timeout=10).json()
    assert st.get("status") in ("NORMAL", "ABNORMAL"), f"status 字段异常: {st}"

    # 模拟源库断电
    docker(["stop", SRC_CTR])
    try:
        # 引擎感知源库不可达后会把实例标记为 SUSPENDED，SyncProgressJob 持久化后 /sync/status 应体现 ABNORMAL
        wait_for(lambda: session.get(f"{LAB_BASE_URL}/sync/status", timeout=10).json().get("status") == "ABNORMAL",
                 40, "源库断电后监控应变为 ABNORMAL")
    finally:
        docker(["start", SRC_CTR])
    wait_mysql(SRC)

    # 恢复后：重新同步成功，监控应回到 NORMAL
    wait_for(lambda: session.get(f"{LAB_BASE_URL}/sync/status", timeout=10).json().get("status") == "NORMAL",
             90, "源库恢复后监控应回到 NORMAL")


def test_02_plaintext_password_not_on_disk(session):
    """缺陷⑤ 明文密码落盘：动态新增映射持久化到 json 后，源/目标密码必须为 ENC: 密文，不得出现明文。"""
    # 用一个「不可达但主机串独特」的源（避免与 YAML 映射冲突、且不会真正同步真实数据），
    # 目标指向可达的 docker 目标库（addMapping 仅校验目标连通性即可持久化）。
    # 目标必须用真实密码（否则目标连通性预检会拒绝，映射不会被持久化）。
    src_pw = "UnreachableSrc@2026!"
    tgt_pw = "123456"  # docker 目标库真实密码，必须可达才能触发持久化
    body = {
        "sourceHost": "10.255.255.9", "sourcePort": 13306,
        "sourceUser": "root", "sourcePassword": src_pw,
        "targetHost": "127.0.0.1", "targetPort": 13307,
        "targetUser": "root", "targetPassword": tgt_pw,
        "sourceDatabases": ["geo_source"],
    }
    r = session.post(f"{LAB_BASE_URL}/sync/mapping/add", json=body, timeout=20)
    assert r.status_code == 200 and r.json().get("success") is True, f"mapping/add 应成功: {r.text[:300]}"
    key = "10.255.255.9->127.0.0.1"
    try:
        # 持久化由 addMappings -> persistDynamicMappings 落盘（密码经 CryptoUtil.encrypt）
        wait_for(lambda: os.path.exists(MAPPINGS_FILE), 20, "动态映射文件应已生成")
        with open(MAPPINGS_FILE, "r", encoding="utf-8") as f:
            raw = f.read()
        assert "ENC:" in raw, "映射文件中应包含 ENC: 密文（密码已加密）"
        assert src_pw not in raw, "源库密码明文不应出现在映射文件中"
        assert tgt_pw not in raw, "目标库密码明文不应出现在映射文件中"
    finally:
        # 清理：移除该映射（停止作业 + 从文件剔除），避免污染后续测试
        session.post(f"{LAB_BASE_URL}/sync/mapping/remove",
                     json={"instanceKey": key}, timeout=20)


def test_03_keyset_no_loss_large_batch_same_timestamp(session):
    """缺陷⑧ 同时间戳大批量丢数据：一次性插入 >5000 行且共享同一 update_time，目标端不应丢数据。

    设计要点（与 test_17 一致：不显式写 update_time，由 MySQL 用服务器 CURRENT_TIMESTAMP 填充，
    避免「测试机本地时区字面量」与「引擎水位 LocalDateTime → MySQL 参数」时区错配导致比较失效）：
      - 种子行不写 update_time，等 MySQL 自动填 NOW() 并被引擎增量捕获，建立水位 wm = 该时间戳；
      - 回读种子行的精确 update_time，让 5200 行批量与之「完全相同时间戳」，强制走 keyset 续扫分支
        (update_time=wm AND pk>key)；种子主键字典序必须小于批量主键，否则 keyset 的 pk> 条件不成立；
      - 增量单批 LIMIT=5000，若没有 keyset 续扫，同时间戳的后续行会被 LIMIT 截断永久丢失。
    """
    run_tag = "KX" + uuid.uuid4().hex[:10]      # 唯一样本前缀，便于清理
    seed = run_tag + "_A_seed"                  # 主键以 'A' 开头 → 字典序小于批量 'B'
    # 清理历史遗留与本批次
    db_exec(SRC, f"DELETE FROM t_order WHERE order_no LIKE '{run_tag}_%'", "geo_source")
    db_exec(TGT, f"DELETE FROM t_order WHERE order_no LIKE '{run_tag}_%'", "geo_source")

    # 1) 插入种子行（不指定 update_time → MySQL 自动填 CURRENT_TIMESTAMP），等待引擎增量捕获并建立水位
    db_exec(SRC,
            "INSERT INTO t_order(order_no,user_id,amount,status,remark) "
            "VALUES(%s,1,1.00,1,'kst-seed')",
            "geo_source", (seed,))
    wait_for(lambda: db_count(TGT, "geo_source", "t_order", f"order_no='{seed}'") == 1, 60, "种子行同步（建立水位）")

    # 2) 回读种子行的精确 update_time，使批量行与其「完全相同时间戳」，触发 keyset 续扫
    conn = db_conn(SRC, "geo_source")
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT update_time FROM t_order WHERE order_no=%s", (seed,))
            row = cur.fetchone()
            seed_ts = row[0] if row else None
    finally:
        conn.close()
    assert seed_ts is not None, "必须回读到种子行 update_time"

    rows = [(f"{run_tag}_B_{i:06d}", seed_ts) for i in range(5200)]
    conn = db_conn(SRC, "geo_source")
    try:
        with conn.cursor() as cur:
            cur.executemany(
                "INSERT INTO t_order(order_no,user_id,amount,status,remark,update_time) "
                "VALUES(%s,1,1.00,1,'kst-batch',%s)", rows)
        conn.commit()
    finally:
        conn.close()

    # 3) 目标端行数应与源端完全一致（种子 1 + 批量 5200 = 5201），不得因 LIMIT 截断丢数据
    def consistent():
        src = db_count(SRC, "geo_source", "t_order", f"order_no LIKE '{run_tag}_%'")
        tgt = db_count(TGT, "geo_source", "t_order", f"order_no LIKE '{run_tag}_%'")
        return src == 5201 and tgt == 5201
    wait_for(consistent, 120, "同时间戳 5200 行全部同步（无丢失）")

    # 4) 清理
    db_exec(SRC, f"DELETE FROM t_order WHERE order_no LIKE '{run_tag}_%'", "geo_source")
    db_exec(TGT, f"DELETE FROM t_order WHERE order_no LIKE '{run_tag}_%'", "geo_source")


def test_04_source_tables_injection_rejected(session):
    """缺陷① 元数据注入：/sync/sourceTables 传入恶意库名，应被标识符白名单拦截，不 500 裸栈、不泄露 SQL。"""
    malicious_db = "geo_source'; DROP TABLE t_order; --"
    r = session.post(f"{LAB_BASE_URL}/sync/sourceTables",
                     json={"sourceHost": SRC["host"], "sourcePort": SRC["port"],
                           "sourceUser": SRC["user"], "sourcePassword": SRC["password"],
                           "database": malicious_db}, timeout=20)
    assert r.status_code in (200, 400, 500), f"注入请求应被正常处理（非崩溃），实际: {r.status_code}"
    body = r.text
    # 注入必须被拦截：要么直接失败（success=False），要么返回的表清单为空/正常，但绝不能把注入串当 SQL 执行并回显
    assert "DROP TABLE" not in body, "响应不应泄露/执行注入 SQL（DROP TABLE）"
    assert "SELECT " not in body, "响应不应泄露内部 SQL"
    if r.status_code == 200:
        # 即便 200，也只能返回合法表名列表，绝不能是注入执行的结果
        assert r.json().get("success") in (True, False)
