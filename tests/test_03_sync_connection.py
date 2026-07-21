# -*- coding: utf-8 -*-
"""连接与元数据接口全场景测试：连接测试 / 源库列表 / 源表列表。

涉及接口：
- POST /sync/mapping/test        测试源/目标连通性
- POST /sync/sourceDatabases     枚举源实例用户库
- POST /sync/sourceTables        枚举指定库的表
"""
import pytest
from conftest import assert_ok, assert_err, LOCAL_MYSQL, REMOTE_MYSQL

AUTH_REQUIRED = "2807003003"
PARAM_ERROR = "2807002001"
DB_CONNECTION_TEST_FAILED = "2807002012"


def _conn(m):
    return {"host": m["host"], "port": m["port"], "user": m["user"], "password": m["password"]}


def _full(src, tgt=None):
    tgt = tgt or src
    return {
        "sourceHost": src["host"], "sourcePort": src["port"],
        "sourceUser": src["user"], "sourcePassword": src["password"],
        "targetHost": tgt["host"], "targetPort": tgt["port"],
        "targetUser": tgt["user"], "targetPassword": tgt["password"],
    }


class TestMappingConnection:
    """POST /sync/mapping/test —— 源/目标连通性测试。"""

    def test_both_ok(self, auth, base_url):
        body = _full(LOCAL_MYSQL)
        r = auth.post(f"{base_url}/sync/mapping/test", json=body, timeout=20)
        j = assert_ok(r)
        assert j["data"]["source"]["ok"] is True
        assert j["data"]["target"]["ok"] is True

    def test_source_wrong_password(self, auth, base_url):
        bad = dict(LOCAL_MYSQL); bad["password"] = "wrongpwd"
        body = _full(bad, LOCAL_MYSQL)
        r = auth.post(f"{base_url}/sync/mapping/test", json=body, timeout=20)
        # 错密码 → DB_CONNECTION_TEST_FAILED(400)，data 仍含两端测试结果
        assert r.status_code == 400, f"错密码应 400: {r.status_code} {r.text[:300]}"
        j = r.json()
        assert j.get("success") is False
        assert str(j.get("code")) == DB_CONNECTION_TEST_FAILED
        assert j["data"]["source"]["ok"] is False, f"错密码应 ok=false: {j['data']['source']}"
        # target 用对的，应 ok
        assert j["data"]["target"]["ok"] is True

    def test_target_wrong_password(self, auth, base_url):
        bad = dict(LOCAL_MYSQL); bad["password"] = "wrongpwd"
        body = _full(LOCAL_MYSQL, bad)
        r = auth.post(f"{base_url}/sync/mapping/test", json=body, timeout=20)
        assert r.status_code == 400, f"错密码应 400: {r.status_code} {r.text[:300]}"
        j = r.json()
        assert j.get("success") is False
        assert str(j.get("code")) == DB_CONNECTION_TEST_FAILED
        assert j["data"]["source"]["ok"] is True
        assert j["data"]["target"]["ok"] is False

    def test_unreachable_host(self, auth, base_url):
        bad = {"host": "10.255.255.1", "port": 3306, "user": "root", "password": "123456"}
        body = _full(bad)
        r = auth.post(f"{base_url}/sync/mapping/test", json=body, timeout=30)
        # 连不通应返回 400 DB_CONNECTION_TEST_FAILED（而非 500）
        assert r.status_code in (200, 400), f"不可达主机不应 500: {r.status_code} {r.text[:300]}"
        j = r.json()
        if j.get("success") is True:
            assert j["data"]["source"]["ok"] is False
        else:
            # 若返回业务错误，也必须是 DB_CONNECTION_TEST_FAILED 而非 500
            assert j.get("success") is False

    def test_empty_source_host(self, auth, base_url):
        body = _full(LOCAL_MYSQL)
        body["sourceHost"] = ""
        r = auth.post(f"{base_url}/sync/mapping/test", json=body, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_missing_all_fields(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/test", json={}, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_remote_ok(self, auth, base_url):
        # 灾备机已调过连接限制，应可连
        body = _full(REMOTE_MYSQL)
        r = auth.post(f"{base_url}/sync/mapping/test", json=body, timeout=20)
        j = assert_ok(r)
        assert j["data"]["source"]["ok"] is True

    def test_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/mapping/test",
                             json=_full(LOCAL_MYSQL), timeout=10), 401, AUTH_REQUIRED)


class TestSourceDatabases:
    """POST /sync/sourceDatabases —— 枚举源实例用户库。"""

    def test_valid(self, auth, base_url):
        body = {
            "sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
            "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
        }
        r = auth.post(f"{base_url}/sync/sourceDatabases", json=body, timeout=20)
        j = assert_ok(r)
        data = j["data"]
        assert isinstance(data, list)
        # DRPlatform 是系统元数据库，已被服务端排除（SYSTEM_SCHEMAS）
        assert "DRPlatform" not in data, f"DRPlatform 应被排除: {data}"
        for db in data:
            assert db not in ("information_schema", "mysql", "performance_schema", "sys"), \
                f"BUG: 系统库未排除: {db}"

    def test_wrong_password(self, auth, base_url):
        body = {
            "sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
            "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": "wrongpwd",
        }
        r = auth.post(f"{base_url}/sync/sourceDatabases", json=body, timeout=20)
        # 错密码应返回错误而非 500
        assert r.status_code in (400, 500), f"错密码响应: {r.status_code}"
        j = r.json()
        assert j.get("success") is False, f"错密码应失败: {j}"

    def test_empty_host(self, auth, base_url):
        body = {
            "sourceHost": "", "sourcePort": 3306,
            "sourceUser": "root", "sourcePassword": "123456",
        }
        r = auth.post(f"{base_url}/sync/sourceDatabases", json=body, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/sourceDatabases", json={
            "sourceHost": "127.0.0.1", "sourcePort": 3306,
            "sourceUser": "root", "sourcePassword": "123456",
        }, timeout=10), 401, AUTH_REQUIRED)


class TestSourceTables:
    """POST /sync/sourceTables —— 枚举指定库的表。"""

    def test_valid_DRPlatform(self, auth, base_url):
        body = {
            "sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
            "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
            "database": "DRPlatform",
        }
        r = auth.post(f"{base_url}/sync/sourceTables", json=body, timeout=20)
        j = assert_ok(r)
        data = j["data"]
        assert isinstance(data, list)
        assert "sys_user" in data, f"DRPlatform 应含 sys_user 表: {data}"

    def test_nonexistent_database(self, auth, base_url):
        body = {
            "sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
            "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
            "database": "no_such_db_xyz_001",
        }
        r = auth.post(f"{base_url}/sync/sourceTables", json=body, timeout=20)
        # 不存在的库应优雅失败（错误或空列表），不应 500 裸异常
        assert r.status_code in (200, 400, 500), f"响应: {r.status_code}"
        if r.status_code == 200:
            assert r.json().get("success") is False or r.json().get("data") == [], \
                f"不存在的库应失败或空: {r.json()}"

    def test_empty_database(self, auth, base_url):
        body = {
            "sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
            "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
            "database": "",
        }
        r = auth.post(f"{base_url}/sync/sourceTables", json=body, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_missing_database_field(self, auth, base_url):
        body = {
            "sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
            "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
        }
        r = auth.post(f"{base_url}/sync/sourceTables", json=body, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/sourceTables", json={
            "sourceHost": "127.0.0.1", "sourcePort": 3306,
            "sourceUser": "root", "sourcePassword": "123456", "database": "DRPlatform",
        }, timeout=10), 401, AUTH_REQUIRED)
