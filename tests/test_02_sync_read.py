# -*- coding: utf-8 -*-
"""同步只读接口全场景测试：状态 / IP列表 / 进度分页 / 映射列表 / 数据库列表 / 详情。

所有 /sync/** 接口均需登录，未登录由 AuthInterceptor 返回 401。
注意：/sync/status、/sync/ipList、/sync/databases/{ips}、/sync/db/list 返回裸结构（非 Result 包装）。
"""
import pytest
from conftest import assert_ok, assert_err

AUTH_REQUIRED = "2807003003"
DATA_NOT_FOUND = "2807002003"
PARAM_ERROR = "2807002001"


class TestSyncStatus:
    """GET /sync/status —— 同步总状态。"""

    def test_status_logged_in(self, auth, base_url):
        r = auth.get(f"{base_url}/sync/status", timeout=10)
        assert r.status_code == 200, r.text
        j = r.json()
        # 裸结构：含 status / desc / firstExceptionTime
        assert "status" in j, f"缺 status 字段: {j}"
        assert "desc" in j, f"缺 desc 字段: {j}"

    def test_status_anonymous_401(self, anon, base_url):
        r = anon.get(f"{base_url}/sync/status", timeout=10)
        assert_err(r, 401, AUTH_REQUIRED)


class TestIpList:
    """GET /sync/ipList —— 节点 IP 列表。"""

    def test_ipList_logged_in(self, auth, base_url):
        r = auth.get(f"{base_url}/sync/ipList", timeout=10)
        assert r.status_code == 200, r.text
        data = r.json()
        assert isinstance(data, list), f"应返回数组: {data}"
        assert len(data) > 0, "IP 列表为空"
        for item in data:
            assert "ip" in item and "type" in item, f"条目缺字段: {item}"

    def test_ipList_anonymous_401(self, anon, base_url):
        assert_err(anon.get(f"{base_url}/sync/ipList", timeout=10), 401, AUTH_REQUIRED)


class TestDbList:
    """POST /sync/db/list —— 同步进度分页查询。"""

    def test_dbList_default(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 5, "condition": {}}, timeout=15)
        assert r.status_code == 200, r.text
        j = r.json()
        # PageRespVO 裸结构
        assert "results" in j, f"缺 results: {j}"
        assert "total" in j, f"缺 total: {j}"
        assert isinstance(j["results"], list)
        # BUG 检测：有结果时 total 应 > 0
        if len(j["results"]) > 0:
            assert j["total"] > 0, f"BUG: results 非空但 total={j['total']}"

    def test_dbList_pagination(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 2, "condition": {}}, timeout=15)
        j = r.json()
        assert len(j["results"]) <= 2, f"pageSize=2 但返回 {len(j['results'])} 条"
        assert "nextPage" in j

    def test_dbList_filter_by_ip(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 50,
                            "condition": {"ip": "127.0.0.1"}}, timeout=15)
        j = r.json()
        for row in j["results"]:
            assert row["sourceIp"] == "127.0.0.1", f"IP 过滤失效: {row.get('sourceIp')}"

    def test_dbList_filter_by_state(self, auth, base_url):
        # state=2 同步中
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 50, "condition": {"state": 2}}, timeout=15)
        j = r.json()
        for row in j["results"]:
            assert row["state"] == 2, f"状态过滤失效: state={row.get('state')}"

    def test_dbList_filter_by_sourceDbName(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 50, "condition": {"sourceDbName": "geo"}}, timeout=15)
        j = r.json()
        for row in j["results"]:
            assert "geo" in row["sourceDbName"].lower(), f"库名模糊过滤失效: {row.get('sourceDbName')}"

    def test_dbList_invalid_page_zero(self, auth, base_url):
        # page=0 应被归一化（不报 500）
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 0, "pageSize": 5, "condition": {}}, timeout=15)
        assert r.status_code == 200, f"page=0 应归一化不报错: {r.status_code} {r.text[:300]}"

    def test_dbList_negative_page(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": -1, "pageSize": 5, "condition": {}}, timeout=15)
        assert r.status_code == 200, f"page=-1 应归一化: {r.status_code} {r.text[:300]}"

    def test_dbList_huge_pagesize(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 100000, "condition": {}}, timeout=15)
        assert r.status_code == 200, f"超大 pageSize 应归一化: {r.status_code} {r.text[:300]}"
        # 实际返回不应真的给 10w 条
        assert len(r.json()["results"]) <= 1000

    def test_dbList_empty_condition(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 5}, timeout=15)
        assert r.status_code == 200, f"缺 condition 应不报错: {r.status_code}"

    def test_dbList_anonymous_401(self, anon, base_url):
        assert_err(anon.post(f"{base_url}/sync/db/list",
                             json={"page": 1, "pageSize": 5, "condition": {}}, timeout=10), 401, AUTH_REQUIRED)


class TestMappings:
    """GET /sync/mappings —— 已配置映射列表。"""

    def test_mappings_logged_in(self, auth, base_url):
        r = auth.get(f"{base_url}/sync/mappings", timeout=10)
        j = assert_ok(r)
        data = j["data"]
        assert isinstance(data, list)
        assert len(data) > 0, "映射列表为空"
        for m in data:
            assert "instanceKey" in m, f"缺 instanceKey: {m}"
            assert "sourceHost" in m and "targetHost" in m
            # 密码必须脱敏
            assert m.get("sourcePasswordMasked") == "******"
            assert m.get("targetPasswordMasked") == "******"
            assert "sourcePassword" not in m, "BUG: 源密码明文泄漏"
            assert "targetPassword" not in m, "BUG: 目标密码明文泄漏"

    def test_mappings_anonymous_401(self, anon, base_url):
        assert_err(anon.get(f"{base_url}/sync/mappings", timeout=10), 401, AUTH_REQUIRED)


class TestDatabasesByIp:
    """GET /sync/databases/{ips} —— 指定 IP 下的用户数据库列表。"""

    def test_databases_single_ip(self, auth, base_url):
        r = auth.get(f"{base_url}/sync/databases/127.0.0.1", timeout=15)
        assert r.status_code == 200, r.text
        data = r.json()
        assert isinstance(data, list)
        # DRPlatform 是系统元数据库，不在同步进度中
        # 系统库应被排除
        for db in data:
            assert db not in ("information_schema", "mysql", "performance_schema", "sys"), \
                f"BUG: 系统库未被排除: {db}"

    def test_databases_anonymous_401(self, anon, base_url):
        assert_err(anon.get(f"{base_url}/sync/databases/127.0.0.1", timeout=10), 401, AUTH_REQUIRED)


class TestSyncDetail:
    """GET /sync/{id} —— 同步进度详情。"""

    def test_detail_valid(self, auth, base_url):
        # 先取一个真实 id
        lst = auth.post(f"{base_url}/sync/db/list",
                        json={"page": 1, "pageSize": 1, "condition": {}}, timeout=15).json()
        assert lst["results"], "无同步进度数据可测"
        first_id = lst["results"][0]["id"]
        r = auth.get(f"{base_url}/sync/{first_id}", timeout=10)
        j = assert_ok(r)
        assert j["data"]["id"] == first_id

    def test_detail_not_found(self, auth, base_url):
        r = auth.get(f"{base_url}/sync/9999999", timeout=10)
        assert_err(r, 404, DATA_NOT_FOUND)

    def test_detail_invalid_id_nonnumeric(self, auth, base_url):
        # 非数字 id 应优雅处理（400 或 404），不应 500
        r = auth.get(f"{base_url}/sync/abc", timeout=10)
        assert r.status_code in (400, 404), f"非数字 id 不应 500: {r.status_code} {r.text[:300]}"

    def test_detail_anonymous_401(self, anon, base_url):
        assert_err(anon.get(f"{base_url}/sync/1", timeout=10), 401, AUTH_REQUIRED)
