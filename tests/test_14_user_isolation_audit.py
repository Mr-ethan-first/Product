# -*- coding: utf-8 -*-
"""用户隔离 + 操作审计日志 + IP 记录 自动化测试。

测试覆盖：
1. 操作日志 - 登录/注册/登出记录 user_id + username + client_ip
2. 操作日志 - 同步操作(测试连接/增删映射/重载/更新)记录审计
3. 操作日志 - 失败操作也记录(FAILURE 状态)
4. 用户隔离 - 用户A的私有映射对用户B不可见
5. 用户隔离 - 全局映射(yml配置)对所有用户可见
6. 操作日志查询 API (/operation-log/list, /operation-log/types)

设计原则：
- 真实 HTTP 调用 + 直连 MySQL 校验 operation_log 表
- 每个测试自包含，失败可独立复现
- 测试结束清理创建的动态映射，避免污染环境
"""
import time
import uuid
import pytest
import requests
import pymysql

from conftest import (
    BASE_URL, ADMIN_USER, ADMIN_PASS, LOCAL_MYSQL, REMOTE_MYSQL,
    assert_ok, assert_err, unique_user, db_query, db_exec, db_query_one,
    ensure_database, drop_database, wait_for_condition,
)

DRPlatform_DB = {"host": "127.0.0.1", "port": 3306, "user": "root", "password": "123456", "database": "DRPlatform"}


# ===================== 辅助函数 =====================

def query_operation_log(operation_type=None, username=None, limit=20):
    """直连 DRPlatform.operation_log 查询最近的操作日志。"""
    sql = "SELECT * FROM operation_log WHERE 1=1"
    args = []
    if operation_type:
        sql += " AND operation_type = %s"
        args.append(operation_type)
    if username:
        sql += " AND username = %s"
        args.append(username)
    sql += " ORDER BY id DESC LIMIT %s"
    args.append(limit)
    return db_query(**DRPlatform_DB, sql=sql, args=args)


def wait_for_op_log(operation_type, timeout=10, interval=0.5):
    """等待指定类型的操作日志出现。"""
    def check():
        rows = query_operation_log(operation_type=operation_type, limit=1)
        return rows[0] if rows else None
    return wait_for_condition(check, timeout=timeout, interval=interval,
                              desc=f"operation_log {operation_type}")


def login_session(username, password):
    """登录并返回 session。"""
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    r = s.post(f"{BASE_URL}/auth/login", json={"username": username, "password": password}, timeout=10)
    return s, r


def register_user(username, password):
    """注册新用户。"""
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    r = s.post(f"{BASE_URL}/auth/register", json={"username": username, "password": password}, timeout=10)
    return s, r


def get_user_id(username):
    """从 sys_user 表查 user_id。"""
    row = db_query_one(**DRPlatform_DB,
                       sql="SELECT id FROM sys_user WHERE username = %s", args=[username])
    return row["id"] if row else None


def create_test_mapping(session, source_host="192.168.88.88", target_host="127.0.0.1"):
    """通过 API 创建测试映射，返回 instanceKey。"""
    body = {
        "sourceHost": source_host, "sourcePort": 3306,
        "sourceUser": "root", "sourcePassword": "123456",
        "targetHost": target_host, "targetPort": 3306,
        "targetUser": "root", "targetPassword": "123456",
        "sourceDatabases": [],
        "ignoreDatabases": ["information_schema", "performance_schema", "mysql", "sys",
                            "DRPlatform", "remote_data_sync"],
    }
    r = session.post(f"{BASE_URL}/sync/mapping/add", json=body, timeout=30)
    return r


def remove_test_mapping(session, instance_key):
    """通过 API 移除测试映射。"""
    return session.post(f"{BASE_URL}/sync/mapping/remove",
                        json={"instanceKey": instance_key}, timeout=15)


def get_mappings(session):
    """查询当前用户可见的映射列表。"""
    r = session.get(f"{BASE_URL}/sync/mappings", timeout=10)
    assert r.status_code == 200, f"查询映射失败: {r.status_code} {r.text[:300]}"
    j = r.json()
    assert j.get("success") is True
    return j.get("data", [])


# ===================== 测试类：操作日志 - 认证类操作 =====================

class TestAuditLogAuth:
    """认证类操作的操作日志测试。"""

    def test_01_register_logs_with_ip(self):
        """注册操作记录审计日志，包含 client_ip。"""
        username = unique_user("audit_reg")
        s, r = register_user(username, "test123456")
        assert r.status_code == 200, f"注册失败: {r.text[:300]}"
        assert_ok(r)

        # 等待操作日志写入
        log = wait_for_op_log("REGISTER", timeout=5)
        assert log is not None, "REGISTER 操作日志未写入"
        assert log["operation_type"] == "REGISTER"
        assert log["result_status"] == "SUCCESS"
        assert log["client_ip"] is not None and log["client_ip"] != "", \
            f"client_ip 未记录: {log}"
        # 注册时用户尚未登录，user_id/username 可为 null（IP 是关键）
        assert log["request_url"] == "/auth/register"

    def test_02_login_logs_with_user_and_ip(self):
        """登录操作记录 user_id + username + client_ip。"""
        username = unique_user("audit_login")
        register_user(username, "test123456")
        uid = get_user_id(username)
        assert uid is not None, f"用户 {username} 未找到"

        s, r = login_session(username, "test123456")
        assert r.status_code == 200, f"登录失败: {r.text[:300]}"
        assert_ok(r)

        log = wait_for_op_log("LOGIN", timeout=5)
        assert log is not None, "LOGIN 操作日志未写入"
        assert log["operation_type"] == "LOGIN"
        assert log["result_status"] == "SUCCESS"
        assert log["username"] == username, f"username 不匹配: {log}"
        assert log["user_id"] == uid, f"user_id 不匹配: 期望 {uid} 实际 {log['user_id']}"
        assert log["client_ip"] is not None and log["client_ip"] != "", \
            f"client_ip 未记录: {log}"
        assert log["request_url"] == "/auth/login"

    def test_03_logout_logs_with_user_and_ip(self):
        """登出操作记录 user_id + username + client_ip。"""
        username = unique_user("audit_logout")
        register_user(username, "test123456")
        uid = get_user_id(username)

        s, r = login_session(username, "test123456")
        assert r.status_code == 200

        # 登出
        r2 = s.post(f"{BASE_URL}/auth/logout", timeout=10)
        assert r2.status_code == 200

        log = wait_for_op_log("LOGOUT", timeout=5)
        assert log is not None, "LOGOUT 操作日志未写入"
        assert log["operation_type"] == "LOGOUT"
        assert log["result_status"] == "SUCCESS"
        assert log["username"] == username, f"username 不匹配: {log}"
        assert log["user_id"] == uid, f"user_id 不匹配: {log}"
        assert log["client_ip"] is not None and log["client_ip"] != "", \
            f"client_ip 未记录: {log}"

    def test_04_failed_login_logs_with_ip(self):
        """登录失败也记录审计日志，result_status=FAILURE，client_ip 有值。"""
        username = unique_user("audit_fail")
        register_user(username, "test123456")

        # 用错误密码登录
        s, r = login_session(username, "wrong_password")
        assert r.status_code != 200 or r.json().get("success") is False

        # 等待 LOGIN 日志（失败也应有记录）
        time.sleep(1)
        logs = query_operation_log(operation_type="LOGIN", limit=5)
        # 找到最近一条 FAILURE 的 LOGIN 记录
        fail_logs = [l for l in logs if l["result_status"] == "FAILURE"]
        assert len(fail_logs) > 0, "未找到失败的 LOGIN 操作日志"
        latest_fail = fail_logs[0]
        assert latest_fail["client_ip"] is not None and latest_fail["client_ip"] != "", \
            f"失败登录的 client_ip 未记录: {latest_fail}"
        assert latest_fail["error_msg"] is not None, "失败登录应记录 error_msg"


# ===================== 测试类：操作日志 - 同步管理类操作 =====================

class TestAuditLogSyncOps:
    """同步管理类操作的操作日志测试。"""

    def test_05_test_connection_logs(self, auth):
        """测试连接操作记录 TEST_CONNECTION 审计日志。"""
        body = {
            "sourceHost": "127.0.0.1", "sourcePort": 3306,
            "sourceUser": "root", "sourcePassword": "123456",
            "targetHost": "192.168.88.88", "targetPort": 3306,
            "targetUser": "root", "targetPassword": "123456",
        }
        r = auth.post(f"{BASE_URL}/sync/mapping/test", json=body, timeout=15)
        # 连接测试可能成功或失败，但都应该记录日志
        time.sleep(1)
        log = wait_for_op_log("TEST_CONNECTION", timeout=5)
        assert log is not None, "TEST_CONNECTION 操作日志未写入"
        assert log["operation_type"] == "TEST_CONNECTION"
        assert log["client_ip"] is not None and log["client_ip"] != "", \
            f"client_ip 未记录: {log}"
        assert log["username"] == ADMIN_USER, f"username 应为 admin: {log}"

    def test_06_mapping_add_logs(self, auth):
        """新增映射操作记录 MAPPING_ADD 审计日志。"""
        r = create_test_mapping(auth)
        # 映射可能已存在（跳过），也可能创建成功
        time.sleep(1)
        log = wait_for_op_log("MAPPING_ADD", timeout=5)
        assert log is not None, "MAPPING_ADD 操作日志未写入"
        assert log["operation_type"] == "MAPPING_ADD"
        assert log["client_ip"] is not None and log["client_ip"] != ""
        assert log["username"] == ADMIN_USER

        # 清理
        instance_key = "192.168.88.88->127.0.0.1"
        try:
            remove_test_mapping(auth, instance_key)
        except Exception:
            pass

    def test_07_mapping_remove_logs(self, auth):
        """移除映射操作记录 MAPPING_REMOVE 审计日志。"""
        # 先创建
        instance_key = "192.168.88.88->127.0.0.1"
        create_test_mapping(auth)
        time.sleep(1)

        # 再移除
        r = remove_test_mapping(auth, instance_key)
        time.sleep(1)
        log = wait_for_op_log("MAPPING_REMOVE", timeout=5)
        assert log is not None, "MAPPING_REMOVE 操作日志未写入"
        assert log["operation_type"] == "MAPPING_REMOVE"
        assert log["client_ip"] is not None and log["client_ip"] != ""
        assert log["username"] == ADMIN_USER

    def test_08_mapping_reload_logs(self, auth):
        """重载映射配置操作记录 MAPPING_RELOAD 审计日志。"""
        # 使用 yml 配置的映射
        instance_key = "127.0.0.1->192.168.88.88"
        r = auth.post(f"{BASE_URL}/sync/mapping/reload",
                      json={"instanceKey": instance_key}, timeout=30)
        time.sleep(1)
        log = wait_for_op_log("MAPPING_RELOAD", timeout=5)
        assert log is not None, "MAPPING_RELOAD 操作日志未写入"
        assert log["operation_type"] == "MAPPING_RELOAD"
        assert log["client_ip"] is not None and log["client_ip"] != ""
        assert log["username"] == ADMIN_USER

    def test_09_mapping_update_logs(self, auth):
        """更新映射配置操作记录 MAPPING_UPDATE 审计日志。"""
        instance_key = "127.0.0.1->192.168.88.88"
        r = auth.post(f"{BASE_URL}/sync/mapping/update",
                      json={"instanceKey": instance_key,
                            "ignoreDatabases": ["day02", "rfm_db"]},
                      timeout=15)
        time.sleep(1)
        log = wait_for_op_log("MAPPING_UPDATE", timeout=5)
        assert log is not None, "MAPPING_UPDATE 操作日志未写入"
        assert log["operation_type"] == "MAPPING_UPDATE"
        assert log["client_ip"] is not None and log["client_ip"] != ""
        assert log["username"] == ADMIN_USER

    def test_10_resync_logs(self, auth):
        """全量重置操作记录 RESYNC 审计日志。"""
        # 对 yml 配置的映射执行 resync
        r = auth.post(f"{BASE_URL}/sync/resyncDatabases",
                      json=[{"ip": "127.0.0.1", "sourceDbName": "test_audit_db"}],
                      timeout=30)
        time.sleep(1)
        log = wait_for_op_log("RESYNC", timeout=5)
        assert log is not None, "RESYNC 操作日志未写入"
        assert log["operation_type"] == "RESYNC"
        assert log["client_ip"] is not None and log["client_ip"] != ""
        assert log["username"] == ADMIN_USER


# ===================== 测试类：用户隔离 =====================

class TestUserIsolation:
    """配置按用户区分：用户只能看到全局映射和自己创建的映射。"""

    def test_11_user_a_private_mapping_invisible_to_user_b(self):
        """用户A创建的私有映射对用户B不可见。"""
        # 1. 注册并登录用户A和用户B
        user_a = unique_user("isol_a")
        user_b = unique_user("isol_b")
        register_user(user_a, "test123456")
        register_user(user_b, "test123456")
        uid_a = get_user_id(user_a)
        uid_b = get_user_id(user_b)

        sa, ra = login_session(user_a, "test123456")
        assert ra.status_code == 200
        sb, rb = login_session(user_b, "test123456")
        assert rb.status_code == 200

        # 2. 用户A创建一个映射
        r = create_test_mapping(sa, source_host="192.168.88.88", target_host="127.0.0.1")
        instance_key = "192.168.88.88->127.0.0.1"

        # 3. 用户A能看到这个映射
        mappings_a = get_mappings(sa)
        a_keys = [m.get("instanceKey") for m in mappings_a]
        assert instance_key in a_keys, \
            f"用户A应能看到自己创建的映射 {instance_key}, 可见: {a_keys}"

        # 验证 userId 绑定正确
        a_private = [m for m in mappings_a if m.get("instanceKey") == instance_key]
        assert len(a_private) == 1
        assert a_private[0].get("userId") == uid_a, \
            f"映射 userId 应为 {uid_a}, 实际 {a_private[0].get('userId')}"

        # 4. 用户B看不到用户A的映射
        mappings_b = get_mappings(sb)
        b_keys = [m.get("instanceKey") for m in mappings_b]
        assert instance_key not in b_keys, \
            f"用户B不应看到用户A的私有映射 {instance_key}, 可见: {b_keys}"

        # 5. 两个用户都能看到全局映射（yml 配置的 127.0.0.1->192.168.88.88）
        global_key = "127.0.0.1->192.168.88.88"
        assert global_key in a_keys, f"用户A应能看到全局映射 {global_key}"
        assert global_key in b_keys, f"用户B应能看到全局映射 {global_key}"

        # 6. 全局映射的 userId 为 null
        a_global = [m for m in mappings_a if m.get("instanceKey") == global_key]
        assert len(a_global) == 1
        assert a_global[0].get("userId") is None, \
            f"全局映射 userId 应为 null, 实际 {a_global[0].get('userId')}"

        # 清理
        try:
            remove_test_mapping(sa, instance_key)
        except Exception:
            pass

    def test_12_user_b_can_see_own_mapping(self):
        """用户B创建自己的映射后能看到，用户A看不到。"""
        user_a = unique_user("isol2_a")
        user_b = unique_user("isol2_b")
        register_user(user_a, "test123456")
        register_user(user_b, "test123456")

        sa, ra = login_session(user_a, "test123456")
        assert ra.status_code == 200
        sb, rb = login_session(user_b, "test123456")
        assert rb.status_code == 200

        # 用户B创建映射
        r = create_test_mapping(sb, source_host="192.168.88.88", target_host="127.0.0.1")
        instance_key = "192.168.88.88->127.0.0.1"

        # 用户B能看到
        mappings_b = get_mappings(sb)
        b_keys = [m.get("instanceKey") for m in mappings_b]
        assert instance_key in b_keys, \
            f"用户B应能看到自己创建的映射 {instance_key}"

        # 用户A看不到
        mappings_a = get_mappings(sa)
        a_keys = [m.get("instanceKey") for m in mappings_a]
        assert instance_key not in a_keys, \
            f"用户A不应看到用户B的私有映射 {instance_key}"

        # 清理
        try:
            remove_test_mapping(sb, instance_key)
        except Exception:
            pass

    def test_13_admin_sees_global_and_own_only(self, auth):
        """admin 用户只能看到全局映射和自己创建的映射（不能看到其他用户的私有映射）。"""
        # 注册普通用户
        user_x = unique_user("isol3_x")
        register_user(user_x, "test123456")
        sx, rx = login_session(user_x, "test123456")
        assert rx.status_code == 200

        # 普通用户创建映射
        create_test_mapping(sx, source_host="192.168.88.88", target_host="127.0.0.1")
        instance_key = "192.168.88.88->127.0.0.1"

        # admin 看不到普通用户的私有映射
        mappings_admin = get_mappings(auth)
        admin_keys = [m.get("instanceKey") for m in mappings_admin]
        assert instance_key not in admin_keys, \
            f"admin 不应看到普通用户的私有映射 {instance_key}, 可见: {admin_keys}"

        # 全局映射 admin 能看到
        global_key = "127.0.0.1->192.168.88.88"
        assert global_key in admin_keys, \
            f"admin 应能看到全局映射 {global_key}"

        # 清理
        try:
            remove_test_mapping(sx, instance_key)
        except Exception:
            pass


# ===================== 测试类：操作日志查询 API =====================

class TestOperationLogQueryAPI:
    """操作审计日志查询接口测试。"""

    def test_14_query_operation_log_list(self, auth):
        """POST /operation-log/list 分页查询操作日志。"""
        # 先做一个操作产生日志
        auth.get(f"{BASE_URL}/sync/mappings", timeout=10)

        r = auth.post(f"{BASE_URL}/operation-log/list",
                      json={"page": 1, "pageSize": 5}, timeout=10)
        j = assert_ok(r)
        data = j.get("data", {})
        assert "results" in data, f"响应缺少 results: {j}"
        assert "total" in data, f"响应缺少 total: {j}"
        assert data["total"] > 0, "操作日志总数应 > 0"
        assert len(data["results"]) <= 5
        # 每条记录应有 clientIp 字段（API 返回 camelCase）
        for log in data["results"]:
            assert "clientIp" in log, f"日志记录缺少 clientIp 字段: {log}"
            assert "operationType" in log
            assert "username" in log
            assert "resultStatus" in log

    def test_15_query_filter_by_operation_type(self, auth):
        """按操作类型过滤操作日志。"""
        r = auth.post(f"{BASE_URL}/operation-log/list",
                      json={"page": 1, "pageSize": 10, "operationType": "LOGIN"},
                      timeout=10)
        j = assert_ok(r)
        results = j.get("data", {}).get("results", [])
        for log in results:
            assert log["operationType"] == "LOGIN", \
                f"过滤 LOGIN 但返回 {log['operationType']}"

    def test_16_query_filter_by_username(self, auth):
        """按用户名过滤操作日志。"""
        r = auth.post(f"{BASE_URL}/operation-log/list",
                      json={"page": 1, "pageSize": 10, "username": ADMIN_USER},
                      timeout=10)
        j = assert_ok(r)
        results = j.get("data", {}).get("results", [])
        for log in results:
            assert log["username"] == ADMIN_USER, \
                f"过滤 username={ADMIN_USER} 但返回 {log.get('username')}"

    def test_17_query_filter_by_result_status(self, auth):
        """按结果状态过滤操作日志。"""
        r = auth.post(f"{BASE_URL}/operation-log/list",
                      json={"page": 1, "pageSize": 10, "resultStatus": "SUCCESS"},
                      timeout=10)
        j = assert_ok(r)
        results = j.get("data", {}).get("results", [])
        for log in results:
            assert log["resultStatus"] == "SUCCESS", \
                f"过滤 SUCCESS 但返回 {log['resultStatus']}"

    def test_18_get_operation_types(self, auth):
        """GET /operation-log/types 获取操作类型列表。"""
        r = auth.get(f"{BASE_URL}/operation-log/types", timeout=10)
        j = assert_ok(r)
        types = j.get("data", {}).get("types", [])
        assert isinstance(types, list)
        assert "LOGIN" in types
        assert "LOGOUT" in types
        assert "MAPPING_ADD" in types
        assert "MAPPING_REMOVE" in types

    def test_19_unauthenticated_access_denied(self, anon):
        """未登录访问操作日志查询返回 401。"""
        r = anon.post(f"{BASE_URL}/operation-log/list",
                      json={"page": 1, "pageSize": 10}, timeout=10)
        assert r.status_code == 401, f"未登录应返回 401, 实际 {r.status_code}"


# ===================== 测试类：IP 记录完整性 =====================

class TestIpRecordingIntegrity:
    """验证所有操作日志都记录了客户端 IP。"""

    def test_20_all_recent_logs_have_ip(self, auth):
        """最近的操作日志都应有 client_ip。"""
        # 触发几个操作
        auth.get(f"{BASE_URL}/sync/mappings", timeout=10)
        auth.get(f"{BASE_URL}/sync/status", timeout=10)

        time.sleep(1)
        # 直连 DB 查最近 20 条
        logs = db_query(**DRPlatform_DB,
                        sql="SELECT * FROM operation_log ORDER BY id DESC LIMIT 20")
        assert len(logs) > 0, "操作日志表为空"

        no_ip = [l for l in logs if l["client_ip"] is None or l["client_ip"] == ""]
        assert len(no_ip) == 0, \
            f"以下操作日志缺少 client_ip: {[(l['id'], l['operation_type']) for l in no_ip]}"

    def test_21_login_log_has_correct_ip(self):
        """登录日志的 client_ip 应为 127.0.0.1（本机测试）。"""
        username = unique_user("ip_test")
        register_user(username, "test123456")
        s, r = login_session(username, "test123456")
        assert r.status_code == 200

        log = wait_for_op_log("LOGIN", timeout=5)
        assert log is not None
        assert log["client_ip"] in ("127.0.0.1", "0:0:0:0:0:0:0:1"), \
            f"client_ip 应为 127.0.0.1, 实际 {log['client_ip']}"

    def test_22_operation_log_has_duration(self, auth):
        """操作日志记录执行耗时 duration_ms。"""
        auth.get(f"{BASE_URL}/sync/mappings", timeout=10)
        time.sleep(1)
        logs = db_query(**DRPlatform_DB,
                        sql="SELECT * FROM operation_log WHERE duration_ms IS NOT NULL ORDER BY id DESC LIMIT 5")
        assert len(logs) > 0, "没有带 duration_ms 的日志"
        for log in logs:
            assert log["duration_ms"] >= 0, f"duration_ms 应 >= 0: {log}"

    def test_23_password_masked_in_params(self):
        """注册/登录操作的 request_params 中密码字段已脱敏。"""
        username = unique_user("mask_test")
        s, r = register_user(username, "supersecret123")
        assert r.status_code == 200

        time.sleep(1)
        logs = db_query(**DRPlatform_DB,
                        sql="SELECT * FROM operation_log WHERE operation_type = 'REGISTER' ORDER BY id DESC LIMIT 1")
        assert len(logs) == 1
        params = logs[0].get("request_params") or ""
        assert "supersecret123" not in params, \
            f"密码明文泄露到 request_params: {params}"
        # 脱敏后应包含 *** 或 password 字段名
        assert "***" in params or "password" not in params.lower(), \
            f"密码字段未脱敏: {params}"
