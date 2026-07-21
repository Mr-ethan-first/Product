# -*- coding: utf-8 -*-
"""GeoDRSync 前后端联调自动化测试 - 公共夹具与断言工具。

设计原则：
- 真实 HTTP 调用，不打 mock，覆盖前后端全链路。
- 每个测试自包含，失败可独立复现。
- 异常场景与正常场景并重。
"""
import os
import re
import time
import uuid
import pytest
import requests
import pymysql

BASE_URL = os.environ.get("GEODRSYNC_BASE_URL", "http://127.0.0.1:8080")
# 已验证可用的管理员账号（上一轮重置过）
ADMIN_USER = os.environ.get("GEODRSYNC_ADMIN_USER", "admin")
ADMIN_PASS = os.environ.get("GEODRSYNC_ADMIN_PASS", "admin123")
# 本机 MySQL（测试用连接参数）
LOCAL_MYSQL = {
    "host": "127.0.0.1", "port": 3306, "user": "root", "password": "123456",
}
# 灾备机 MySQL（已调过 max_user_connections，可连）
REMOTE_MYSQL = {
    "host": "192.168.88.88", "port": 3306, "user": "root", "password": "123456",
}
# 同步引擎轮询间隔（application.yml: engine.poll-interval-ms=2000）
SYNC_POLL_MS = 2000


@pytest.fixture(scope="session")
def base_url():
    return BASE_URL


@pytest.fixture
def anon():
    """匿名会话（未登录）。"""
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    return s


@pytest.fixture
def auth(anon):
    """已登录 admin 的会话。"""
    r = anon.post(f"{BASE_URL}/auth/login",
                  json={"username": ADMIN_USER, "password": ADMIN_PASS}, timeout=10)
    assert r.status_code == 200, f"admin 登录失败: {r.status_code} {r.text}"
    j = r.json()
    assert j.get("success") is True, f"admin 登录 success=false: {j}"
    return anon


def assert_ok(r, expect_status=200):
    """断言 HTTP 200 + Result.success=True。"""
    assert r.status_code == expect_status, f"HTTP {r.status_code}: {r.text[:500]}"
    j = r.json()
    assert j.get("success") is True, f"success=False: {j}"
    return j


def assert_err(r, expect_status, expect_code=None):
    """断言错误响应：HTTP 状态码 + success=False + 可选错误码。"""
    assert r.status_code == expect_status, f"期望 HTTP {expect_status} 实际 {r.status_code}: {r.text[:500]}"
    j = r.json()
    assert j.get("success") is False, f"期望 success=False 实际 True: {j}"
    if expect_code is not None:
        assert str(j.get("code")) == str(expect_code), f"期望 code={expect_code} 实际 {j.get('code')}: {j}"
    return j


def unique_user(prefix="ut"):
    """生成唯一测试用户名（避免重复注册冲突）。"""
    return f"{prefix}_{int(time.time())}_{uuid.uuid4().hex[:6]}"


def mysql_conn(host, port, user, password):
    """构造 mapping 接口所需的 MySQL 连接参数 dict。"""
    return {"host": host, "port": port, "user": user, "password": password}


# ===================== 数据库直连辅助函数 =====================

def db_connect(host="127.0.0.1", port=3306, user="root", password="123456", database=None):
    """直连 MySQL，返回 pymysql.Connection。调用方需 with 或 try/finally close。"""
    return pymysql.connect(
        host=host, port=port, user=user, password=password,
        database=database, charset="utf8mb4",
        connect_timeout=10, read_timeout=30, write_timeout=30,
    )


def db_exec(host, port, user, password, sql, args=None, database=None, fetch=False):
    """执行单条 SQL，返回 fetch 结果（如果 fetch=True）。

    返回值约定：
    - fetch=True 时始终返回 list[dict]，空结果为 []（不是 tuple）。
    - 所有 dict key 统一小写，消除 MySQL 列名大小写不一致问题
     （sync_progress 用大写 SOURCE_IP，sys_user 用小写 username）。
    """
    conn = db_connect(host, port, user, password, database)
    try:
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(sql, args or ())
            if fetch:
                return [{k.lower(): v for k, v in row.items()} for row in cur.fetchall()]
            conn.commit()
            return cur.rowcount
    finally:
        conn.close()


def db_query(host, port, user, password, sql, args=None, database=None):
    """查询并返回所有行（DictCursor）。"""
    return db_exec(host, port, user, password, sql, args, database, fetch=True)


def db_query_one(host, port, user, password, sql, args=None, database=None):
    """查询并返回单行。"""
    rows = db_query(host, port, user, password, sql, args, database)
    return rows[0] if rows else None


def ensure_database(host, port, user, password, db_name, drop_if_exists=False):
    """创建数据库（如果不存在）。drop_if_exists=True 则先 DROP 再 CREATE。"""
    if drop_if_exists:
        db_exec(host, port, user, password, f"DROP DATABASE IF EXISTS `{db_name}`")
    db_exec(host, port, user, password,
            f"CREATE DATABASE IF NOT EXISTS `{db_name}` DEFAULT CHARACTER SET utf8mb4")


def drop_database(host, port, user, password, db_name):
    """删除数据库（如果存在）。"""
    db_exec(host, port, user, password, f"DROP DATABASE IF EXISTS `{db_name}`")


def wait_for_sync(cycles=3, extra_ms=500):
    """等待同步引擎完成若干轮扫描（默认 3 轮 ≈ 6s + 0.5s 余量）。"""
    time.sleep(cycles * (SYNC_POLL_MS / 1000) + extra_ms / 1000)


def wait_for_condition(fn, timeout=30, interval=1, desc="condition"):
    """轮询等待条件成立。fn 返回真值时立即返回，超时抛 AssertionError。

    异常容错：同步引擎尚未在灾备端建表/建库时，查询会抛 ProgrammingError，
    此类瞬时异常视为「条件未满足」继续轮询，不立即中断测试。
    """
    deadline = time.time() + timeout
    last = None
    last_error = None
    while time.time() < deadline:
        try:
            last = fn()
            if last:
                return last
        except Exception as e:
            last_error = str(e)
            last = None
        time.sleep(interval)
    raise AssertionError(
        f"等待 {desc} 超时（{timeout}s），最后结果: {last}，最后异常: {last_error}"
    )


def get_sync_progress(auth, base_url, source_ip=None, source_db=None):
    """查询同步进度列表，可选按 IP/库名过滤。

    注意：/sync/db/list 返回 PageRespVO（{total, nextPage, results}），
    不是标准 Result 包装（无 success 字段），因此不能使用 assert_ok。
    """
    body = {"page": 1, "pageSize": 200}
    if source_ip:
        body["ip"] = source_ip
    if source_db:
        body["sourceDbName"] = source_db
    r = auth.post(f"{base_url}/sync/db/list", json=body, timeout=15)
    assert r.status_code == 200, f"查询同步进度失败: HTTP {r.status_code}: {r.text[:300]}"
    j = r.json()
    # PageRespVO 直接返回 {total, nextPage, results}，无 success 包装
    return j.get("results", []), j.get("total", 0)
