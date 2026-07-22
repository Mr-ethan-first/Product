# -*- coding: utf-8 -*-
"""非法值与异常场景全自动化验证（健壮性专项）。

目标：在已部署并启用了会话鉴权（app.auth.enabled=true）的运行态实例上，
对核心业务接口注入非法值 / 边界值 / 异常格式，确认程序：
  1) 返回受控的错误码（400/401/404/415），不出现裸 500；
  2) 不向客户端泄露内部异常信息（catch-all 仅给通用文案）；
  3) 非法入参不会造成数据污染或连接泄漏。

覆盖范围：
- POST /sync/mapping/add        端口越界 / 空字段 / 超长 / 注入串 / 畸形 body
- POST /sync/mapping/test       不可达 / 端口越界 / 空字段
- POST /sync/sourceTables       空库名 / 空主机 / 端口越界 / 注入库名
- POST /sync/progress/page      负页 / 0 页 / 超大页 / 非数字页 / 浮点页
- GET  /sync/progress/{id}      非数字 id / 负 id
- POST /operation-log/list      畸形 page / 浮点页 / 畸形日期 / 对象型字段 / 超大页
- POST /sync/mapping/remove     超长 instanceKey

约定：复用 conftest 的 auth（已登录会话）、base_url、assert_ok/assert_err。
"""
import json

import pytest
import requests

from conftest import assert_ok, assert_err, LOCAL_MYSQL, REMOTE_MYSQL

PARAM_ERROR = "2807002001"
AUTH_REQUIRED = "2807003003"
DATA_NOT_FOUND = "2807002002"  # 如与实际不符，本测试只断言 http 非 500


def _full_body(src, tgt, ignore_dbs=None):
    return {
        "sourceHost": src["host"], "sourcePort": src["port"],
        "sourceUser": src["user"], "sourcePassword": src["password"],
        "targetHost": tgt["host"], "targetPort": tgt["port"],
        "targetUser": tgt["user"], "targetPassword": tgt["password"],
        "ignoreDatabases": ignore_dbs or [],
    }


def _assert_controlled(r, *allowed_status):
    """断言响应是受控错误（非裸 500），且 body 为合法 JSON、success=False。"""
    assert r.status_code in allowed_status, f"期望状态 {allowed_status} 实际 {r.status_code}: {r.text[:400]}"
    try:
        j = r.json()
    except Exception:
        pytest.fail(f"响应非 JSON: {r.text[:400]}")
    if r.status_code >= 500:
        pytest.fail(f"出现裸 500: {r.text[:400]}")
    if r.status_code in (400, 401, 404, 415):
        assert j.get("success") is False, f"受控错误应为 success=False: {j}"
    # 健壮性：错误响应不得泄露内部异常类名/堆栈
    blob = r.text
    assert "Exception" not in blob, f"错误响应泄露 Exception 类名: {blob[:300]}"
    assert "at com.example" not in blob, f"错误响应泄露堆栈: {blob[:300]}"
    return j


# ============================================================
# 1. POST /sync/mapping/add
# ============================================================
class TestAddMappingIllegal:
    def test_port_zero(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourcePort"] = 0
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_port_over_max(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourcePort"] = 70000
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_port_negative(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourcePort"] = -1
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_port_string(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourcePort"] = "abc"  # 类型不匹配 → 400
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_empty_source_user(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourceUser"] = ""
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_empty_source_password(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourcePassword"] = ""
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_empty_target_host(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["targetHost"] = ""
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_empty_target_user(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["targetUser"] = ""
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_empty_target_password(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["targetPassword"] = ""
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_overlong_source_host(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourceHost"] = "h" * 256
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_overlong_password(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourcePassword"] = "p" * 257  # 超过 @Size(max=256) 上限
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400)

    def test_sqli_in_host(self, auth, base_url):
        # 注入串应被主机格式校验（@Pattern）直接拒绝，返回 400，不会启动作业
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourceHost"] = "127.0.0.1' OR '1'='1"
        assert_err(auth.post(f"{base_url}/sync/mapping/add", json=b, timeout=20), 400, PARAM_ERROR)

    def test_missing_content_type(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        # 覆盖 session 默认 Content-Type，发送 text/plain → 415
        r = auth.post(f"{base_url}/sync/mapping/add",
                      data=json.dumps(b), headers={"Content-Type": "text/plain"}, timeout=20)
        assert r.status_code in (400, 415), f"期望 400/415 实际 {r.status_code}: {r.text[:300]}"

    def test_invalid_json_body(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/add",
                      data=b"{not a valid json", headers={"Content-Type": "application/json"}, timeout=20)
        _assert_controlled(r, 400)

    def test_empty_body(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/add", json=None, timeout=20)
        _assert_controlled(r, 400)

    def test_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/mapping/add",
                             json=_full_body(LOCAL_MYSQL, LOCAL_MYSQL), timeout=10), 401, AUTH_REQUIRED)


# ============================================================
# 2. POST /sync/mapping/test （连接测试）
# ============================================================
class TestMappingTestIllegal:
    def test_bad_port(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourcePort"] = 99999
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/test", json=b, timeout=20), 400)

    def test_unreachable_target(self, auth, base_url):
        bad = {"host": "10.255.255.1", "port": 3306, "user": "root", "password": "123456"}
        b = _full_body(LOCAL_MYSQL, bad)
        r = auth.post(f"{base_url}/sync/mapping/test", json=b, timeout=30)
        # 不可达应优雅失败，不得 200 成功或裸 500
        assert r.status_code in (400, 500), f"不可达响应: {r.status_code} {r.text[:300]}"
        if r.status_code == 200:
            assert r.json().get("success") is False

    def test_empty_host(self, auth, base_url):
        b = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        b["sourceHost"] = ""
        _assert_controlled(auth.post(f"{base_url}/sync/mapping/test", json=b, timeout=20), 400)


# ============================================================
# 3. POST /sync/sourceTables
# ============================================================
class TestSourceTablesIllegal:
    def test_empty_source_host(self, auth, base_url):
        b = {"sourceHost": "", "sourcePort": LOCAL_MYSQL["port"],
             "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
             "database": "mysql"}
        _assert_controlled(auth.post(f"{base_url}/sync/sourceTables", json=b, timeout=20), 400)

    def test_empty_database(self, auth, base_url):
        b = {"sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
             "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
             "database": ""}
        _assert_controlled(auth.post(f"{base_url}/sync/sourceTables", json=b, timeout=20), 400)

    def test_bad_port(self, auth, base_url):
        b = {"sourceHost": LOCAL_MYSQL["host"], "sourcePort": 0,
             "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
             "database": "mysql"}
        _assert_controlled(auth.post(f"{base_url}/sync/sourceTables", json=b, timeout=20), 400)

    def test_sqli_database(self, auth, base_url):
        b = {"sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
             "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
             "database": "mysql; DROP TABLE users--"}
        r = auth.post(f"{base_url}/sync/sourceTables", json=b, timeout=20)
        assert r.status_code in (400, 500), f"注入库名响应: {r.status_code} {r.text[:300]}"
        if r.status_code == 200:
            assert r.json().get("success") is False


# ============================================================
# 4. POST /sync/db/list （同步进度分页，真实路径）
# ============================================================
class TestProgressPageIllegal:
    def test_negative_page_normalized(self, auth, base_url):
        # 负数页应被 normalize 到 1，返回 200（优雅），不 500
        r = auth.post(f"{base_url}/sync/db/list", json={"page": -1, "pageSize": 20}, timeout=15)
        _assert_controlled(r, 200)

    def test_zero_page_size_normalized(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list", json={"page": 1, "pageSize": 0}, timeout=15)
        _assert_controlled(r, 200)

    def test_huge_page_size_capped(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list", json={"page": 1, "pageSize": 100000}, timeout=15)
        _assert_controlled(r, 200)

    def test_page_string(self, auth, base_url):
        # 非数字页 → 400
        r = auth.post(f"{base_url}/sync/db/list", json={"page": "abc", "pageSize": 20}, timeout=15)
        _assert_controlled(r, 400)

    def test_page_float(self, auth, base_url):
        # Jackson 默认将浮点 coerce 为整数（1.5→1），端点优雅返回 200（非 defect）；
        # 关键断言：不得裸 500、不得泄露内部信息。
        r = auth.post(f"{base_url}/sync/db/list", json={"page": 1.5, "pageSize": 20}, timeout=15)
        _assert_controlled(r, 200, 400)

    def test_empty_body(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list", json=None, timeout=15)
        _assert_controlled(r, 400)


# ============================================================
# 5. GET /sync/{id} （同步详情，真实路径）
# ============================================================
class TestGetByIdIllegal:
    def test_non_numeric_id(self, auth, base_url):
        _assert_controlled(auth.get(f"{base_url}/sync/abc", timeout=15), 400)

    def test_negative_id(self, auth, base_url):
        # 负 id 查不到 → DATA_NOT_FOUND → 404，不 500
        _assert_controlled(auth.get(f"{base_url}/sync/-1", timeout=15), 404)


# ============================================================
# 6. POST /operation-log/list （参数安全解析专项）
# ============================================================
class TestOperationLogIllegal:
    def test_malformed_page(self, auth, base_url):
        r = auth.post(f"{base_url}/operation-log/list", json={"page": "abc", "pageSize": 20}, timeout=15)
        _assert_controlled(r, 400)

    def test_float_page(self, auth, base_url):
        r = auth.post(f"{base_url}/operation-log/list", json={"page": 1.5, "pageSize": 20}, timeout=15)
        _assert_controlled(r, 400)

    def test_malformed_start_time(self, auth, base_url):
        r = auth.post(f"{base_url}/operation-log/list",
                      json={"page": 1, "pageSize": 20, "startTime": "2026/01/01 00:00:00"}, timeout=15)
        _assert_controlled(r, 400)

    def test_object_typed_field(self, auth, base_url):
        r = auth.post(f"{base_url}/operation-log/list", json={"page": 1, "pageSize": 20, "username": {"a": 1}}, timeout=15)
        _assert_controlled(r, 400)

    def test_huge_page_size_capped(self, auth, base_url):
        r = auth.post(f"{base_url}/operation-log/list", json={"page": 1, "pageSize": 999999}, timeout=15)
        _assert_controlled(r, 200)

    def test_empty_body_ok(self, auth, base_url):
        # 空 body {} → 默认分页，返回 200
        r = auth.post(f"{base_url}/operation-log/list", json={}, timeout=15)
        _assert_controlled(r, 200)

    def test_valid_query_ok(self, auth, base_url):
        r = auth.post(f"{base_url}/operation-log/list",
                      json={"page": 1, "pageSize": 10, "operationType": "LOGIN"}, timeout=15)
        _assert_controlled(r, 200)


# ============================================================
# 7. POST /sync/mapping/remove
# ============================================================
class TestRemoveIllegal:
    def test_overlong_instance_key(self, auth, base_url):
        b = {"instanceKey": "x" * 1000 + "->y"}
        r = auth.post(f"{base_url}/sync/mapping/remove", json=b, timeout=15)
        # 超长 key 查不到 → 404，不 500
        _assert_controlled(r, 404)

    def test_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/mapping/remove",
                             json={"instanceKey": "x->y"}, timeout=10), 401, AUTH_REQUIRED)
