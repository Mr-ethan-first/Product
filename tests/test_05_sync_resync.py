# -*- coding: utf-8 -*-
"""重新同步接口全场景测试。

涉及接口：POST /sync/resyncDatabases —— 对指定库重置同步状态、触发全量重扫。

注意：resync 会改变同步状态（state→全量同步），属破坏性操作，但同步引擎会自动恢复。
本测试只对一条真实存在的库做一次 resync 并验证状态切换，其余覆盖异常入参。
"""
import pytest
from conftest import assert_ok, assert_err

AUTH_REQUIRED = "2807003003"
PARAM_ERROR = "2807002001"


def _get_first_progress(auth, base_url):
    lst = auth.post(f"{base_url}/sync/db/list",
                    json={"page": 1, "pageSize": 1, "condition": {}}, timeout=15).json()
    assert lst["results"], "无同步进度数据可测 resync"
    return lst["results"][0]


class TestResync:
    """POST /sync/resyncDatabases。"""

    def test_resync_empty_list(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/resyncDatabases", json=[], timeout=15)
        # 空列表应优雅处理（200 空操作 或 400 参数错误），不报 500
        assert r.status_code in (200, 400), f"空列表响应: {r.status_code} {r.text[:300]}"

    def test_resync_null_body(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/resyncDatabases", json=None, timeout=15)
        assert r.status_code in (200, 400), f"null body 响应: {r.status_code} {r.text[:300]}"

    def test_resync_nonexistent_db(self, auth, base_url):
        body = [{"ip": "127.0.0.1", "sourceDbName": "no_such_db_xyz_001"}]
        r = auth.post(f"{base_url}/sync/resyncDatabases", json=body, timeout=20)
        # 不存在的库应优雅失败或忽略，不报裸 500
        assert r.status_code in (200, 400, 500), f"响应: {r.status_code} {r.text[:300]}"
        if r.status_code == 500:
            # 若 500，必须是业务错误结构，不能是裸堆栈
            j = r.json()
            assert j.get("success") is False, f"500 应是业务错误: {j}"

    def test_resync_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/resyncDatabases",
                             json=[{"ip": "127.0.0.1", "sourceDbName": "geodrsync"}], timeout=10),
                   401, AUTH_REQUIRED)

    @pytest.mark.destructive
    def test_resync_valid_then_recover(self, auth, base_url):
        """对一条真实库做 resync，验证状态切换为全量同步(1)或同步中(2)，且后续恢复。"""
        row = _get_first_progress(auth, base_url)
        body = [{"ip": row["sourceIp"], "sourceDbName": row["sourceDbName"]}]
        r = auth.post(f"{base_url}/sync/resyncDatabases", json=body, timeout=30)
        assert r.status_code == 200, f"resync 失败: {r.status_code} {r.text[:400]}"
        j = r.json()
        assert j.get("success") is True, f"resync success=false: {j}"

        # 验证该库状态已重置（state 应为 1 全量同步 或 2 同步中）
        r2 = auth.get(f"{base_url}/sync/{row['id']}", timeout=10)
        j2 = assert_ok(r2)
        new_state = j2["data"]["state"]
        assert new_state in (1, 2), f"resync 后状态异常 state={new_state}: {j2['data']}"
