# -*- coding: utf-8 -*-
"""同步映射增删接口全场景测试。

涉及接口：
- POST /sync/mapping/add      新增主机对映射（启动同步作业）
- POST /sync/mapping/remove   移除主机对映射（停止作业）

设计说明：
- 重复添加用已存在的 127.0.0.1->127.0.0.1 触发 ALREADY_EXISTS（无副作用）。
- happy-path 增删循环用 192.168.88.88->127.0.0.1，并在 add 时把远端全部用户库
  加入 ignoreDatabases，确保新作业空转、零数据副作用，随后立即 remove。
"""
import time
import pytest
from conftest import assert_ok, assert_err, LOCAL_MYSQL, REMOTE_MYSQL

AUTH_REQUIRED = "2807003003"
PARAM_ERROR = "2807002001"
MAPPING_ALREADY_EXISTS = "2807002013"
MAPPING_REMOVE_NOT_FOUND = "2807002014"


def _full_body(src, tgt, ignore_dbs=None):
    return {
        "sourceHost": src["host"], "sourcePort": src["port"],
        "sourceUser": src["user"], "sourcePassword": src["password"],
        "targetHost": tgt["host"], "targetPort": tgt["port"],
        "targetUser": tgt["user"], "targetPassword": tgt["password"],
        "ignoreDatabases": ignore_dbs or [],
    }


class TestMappingAdd:
    """POST /sync/mapping/add。"""

    def test_add_duplicate(self, auth, base_url):
        # 127.0.0.1->127.0.0.1 已存在（dynamic）
        body = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        r = auth.post(f"{base_url}/sync/mapping/add", json=body, timeout=20)
        # 重复应返回 ALREADY_EXISTS（skipped），不报 500
        assert r.status_code in (200, 400), f"重复添加响应: {r.status_code} {r.text[:300]}"
        j = r.json()
        if j.get("success") is False:
            assert str(j.get("code")) == MAPPING_ALREADY_EXISTS, f"重复添加错误码: {j}"
        else:
            # 批量结果形式：skipped 非空
            data = j.get("data", {})
            assert "skipped" in data, f"重复应进 skipped: {data}"

    def test_add_missing_fields(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/add", json={}, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_add_empty_source_host(self, auth, base_url):
        body = _full_body(LOCAL_MYSQL, LOCAL_MYSQL)
        body["sourceHost"] = ""
        r = auth.post(f"{base_url}/sync/mapping/add", json=body, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_add_unreachable_target(self, auth, base_url):
        bad_tgt = {"host": "10.255.255.1", "port": 3306, "user": "root", "password": "123456"}
        body = _full_body(LOCAL_MYSQL, bad_tgt)
        r = auth.post(f"{base_url}/sync/mapping/add", json=body, timeout=30)
        # 不可达目标应失败（连接测试未通过或新增失败），不报裸 500
        assert r.status_code in (400, 500), f"响应: {r.status_code} {r.text[:300]}"
        j = r.json()
        assert j.get("success") is False, f"不可达目标不应成功: {j}"

    def test_add_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/mapping/add",
                             json=_full_body(LOCAL_MYSQL, LOCAL_MYSQL), timeout=10), 401, AUTH_REQUIRED)


class TestMappingRemove:
    """POST /sync/mapping/remove。"""

    def test_remove_nonexistent(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/remove",
                      json={"instanceKey": "9.9.9.9->8.8.8.8"}, timeout=15)
        assert_err(r, 404, MAPPING_REMOVE_NOT_FOUND)

    def test_remove_empty_key(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/remove",
                      json={"instanceKey": ""}, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_remove_missing_key(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/remove", json={}, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_remove_null_body(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/mapping/remove", json=None, timeout=15)
        assert_err(r, 400, PARAM_ERROR)

    def test_remove_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/mapping/remove",
                             json={"instanceKey": "x->y"}, timeout=10), 401, AUTH_REQUIRED)


class TestMappingAddRemoveCycle:
    """happy-path：动态新增一个空转映射，再移除，验证列表一致性。"""

    def test_add_then_remove(self, auth, base_url):
        # 1) 取远端用户库列表，全部 ignore → 新作业空转，零数据副作用
        r = auth.post(f"{base_url}/sync/sourceDatabases", json={
            "sourceHost": REMOTE_MYSQL["host"], "sourcePort": REMOTE_MYSQL["port"],
            "sourceUser": REMOTE_MYSQL["user"], "sourcePassword": REMOTE_MYSQL["password"],
        }, timeout=20)
        remote_dbs = assert_ok(r)["data"]

        key = f"{REMOTE_MYSQL['host']}->{LOCAL_MYSQL['host']}"
        body = _full_body(REMOTE_MYSQL, LOCAL_MYSQL, ignore_dbs=remote_dbs)

        # 2) 新增
        r = auth.post(f"{base_url}/sync/mapping/add", json=body, timeout=30)
        assert r.status_code == 200, f"新增失败: {r.status_code} {r.text[:400]}"
        j = r.json()
        assert j.get("success") is True, f"新增 success=false: {j}"
        created = j.get("data", {}).get("created", [])
        assert key in created, f"未出现在 created: {j.get('data')}"

        # 3) 列表应包含新 key
        time.sleep(1)
        r = auth.get(f"{base_url}/sync/mappings", timeout=10)
        j = assert_ok(r)
        keys = [m["instanceKey"] for m in j["data"]]
        assert key in keys, f"新增后列表未见 {key}: {keys}"

        # 4) 移除
        r = auth.post(f"{base_url}/sync/mapping/remove",
                      json={"instanceKey": key}, timeout=20)
        assert_ok(r)

        # 5) 列表应不再包含
        time.sleep(1)
        r = auth.get(f"{base_url}/sync/mappings", timeout=10)
        j = assert_ok(r)
        keys = [m["instanceKey"] for m in j["data"]]
        assert key not in keys, f"移除后列表仍含 {key}: {keys}"
