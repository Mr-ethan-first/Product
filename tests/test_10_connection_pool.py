# -*- coding: utf-8 -*-
"""DynamicShardedConnectionManager 连接池管理并发测试。

验证后端连接池（HikariCP + 全局信号量 maxPoolSize=32 / maxGlobalConnections=200）
在高并发 API 调用下不泄漏、不阻塞、不返回 5xx 裸堆栈。

涉及接口：
- GET  /sync/status            同步总状态（裸结构：status / desc / firstExceptionTime）
- GET  /sync/mappings          映射列表（Result 包装，密码脱敏 ******）
- POST /sync/mapping/test      连接测试（Result 包装，含 source/target 测试结果）
- POST /sync/sourceDatabases   源实例用户库枚举（Result 包装）
- POST /sync/db/list           同步进度分页查询（裸 PageRespVO：total / nextPage / results）

设计要点：
- requests.Session 非线程安全，并发测试从 auth fixture 提取 JSESSIONID Cookie，
  各线程用模块级 requests.get/post 发起独立请求（每次创建临时 Session，线程安全）。
- 连接泄漏检测：连续多次调用后追加一次请求，若连接池泄漏则后续请求会超时或失败。
"""
import threading
from concurrent.futures import ThreadPoolExecutor, as_completed

import requests

from conftest import *  # noqa: F401,F403  assert_ok / assert_err / LOCAL_MYSQL / REMOTE_MYSQL / db_query ...


def _auth_cookies(auth):
    """从 auth fixture 提取会话 Cookie dict，供线程池内独立请求使用。"""
    return auth.cookies.get_dict()


def _full_conn_body(src, tgt):
    """构造 /sync/mapping/test 与 /sync/mapping/add 所需的完整连接 body。"""
    return {
        "sourceHost": src["host"], "sourcePort": src["port"],
        "sourceUser": src["user"], "sourcePassword": src["password"],
        "targetHost": tgt["host"], "targetPort": tgt["port"],
        "targetUser": tgt["user"], "targetPassword": tgt["password"],
    }


class TestConnectionPoolConcurrent:
    """连接池并发负载与泄漏检测。"""

    def test_concurrent_api_calls(self, auth, base_url):
        """20 个 /sync/status 并发请求，全部应返回 200 且含 status 字段。

        /sync/status 返回裸结构（非 Result 包装），无 success 字段；
        以 HTTP 200 + status 字段存在作为"请求成功"判据。
        """
        cookies = _auth_cookies(auth)
        url = f"{base_url}/sync/status"
        lock = threading.Lock()
        results = []
        errors = []

        def call(_idx):
            try:
                r = requests.get(url, cookies=cookies, timeout=15)
                j = r.json() if r.status_code == 200 else {}
                return (r.status_code, j)
            except Exception as e:  # noqa: BLE001
                return (-1, {"__error__": str(e)})

        with ThreadPoolExecutor(max_workers=20) as ex:
            futures = [ex.submit(call, i) for i in range(20)]
            for f in as_completed(futures):
                status, body = f.result()
                with lock:
                    results.append(status)
                    if status != 200 or "__error__" in body:
                        errors.append((status, body))

        assert len(results) == 20, f"并发完成数异常: 期望 20 实际 {len(results)}"
        assert all(s == 200 for s in results), \
            f"存在非 200 响应: {results}"
        assert not errors, f"并发 /sync/status 错误: {errors[:3]}"

        # 二次校验：裸结构应含 status / desc 字段
        sample_status, sample_body = call(0)
        assert sample_status == 200
        assert "status" in sample_body, f"/sync/status 缺 status 字段: {sample_body}"
        assert "desc" in sample_body, f"/sync/status 缺 desc 字段: {sample_body}"

    def test_concurrent_mapping_list(self, auth, base_url):
        """10 个 /sync/mappings 并发请求，全部 200 + success=true + 密码脱敏 ******。"""
        cookies = _auth_cookies(auth)
        url = f"{base_url}/sync/mappings"
        lock = threading.Lock()
        responses = []

        def call(_idx):
            try:
                r = requests.get(url, cookies=cookies, timeout=15)
                return (r.status_code, r.json())
            except Exception as e:  # noqa: BLE001
                return (-1, {"__error__": str(e)})

        with ThreadPoolExecutor(max_workers=10) as ex:
            futures = [ex.submit(call, i) for i in range(10)]
            for f in as_completed(futures):
                with lock:
                    responses.append(f.result())

        assert len(responses) == 10, f"并发完成数异常: 期望 10 实际 {len(responses)}"

        for status, body in responses:
            assert status == 200, f"HTTP {status}: {body}"
            assert body.get("success") is True, f"success=false: {body}"
            data = body.get("data", [])
            assert isinstance(data, list), f"data 非数组: {data}"
            for m in data:
                # 密码必须脱敏
                assert m.get("sourcePasswordMasked") == "******", \
                    f"源密码未脱敏: {m}"
                assert m.get("targetPasswordMasked") == "******", \
                    f"目标密码未脱敏: {m}"
                assert "sourcePassword" not in m, "BUG: 源密码明文泄漏"
                assert "targetPassword" not in m, "BUG: 目标密码明文泄漏"

    def test_connection_test_repeated(self, auth, base_url):
        """连续 10 次 /sync/mapping/test（相同参数），全部成功且无连接泄漏。"""
        cookies = _auth_cookies(auth)
        url = f"{base_url}/sync/mapping/test"
        body = _full_conn_body(LOCAL_MYSQL, LOCAL_MYSQL)

        for i in range(10):
            r = requests.post(url, json=body, cookies=cookies, timeout=20)
            assert r.status_code == 200, \
                f"第 {i + 1} 次连接测试 HTTP {r.status_code}: {r.text[:300]}"
            j = r.json()
            assert j.get("success") is True, f"第 {i + 1} 次 success=false: {j}"
            assert j["data"]["source"]["ok"] is True, \
                f"第 {i + 1} 次源库连通失败: {j['data']['source']}"
            assert j["data"]["target"]["ok"] is True, \
                f"第 {i + 1} 次目标库连通失败: {j['data']['target']}"

        # 连接泄漏检测：第 11 次请求应仍能正常响应
        r = requests.post(url, json=body, cookies=cookies, timeout=20)
        assert r.status_code == 200, \
            f"连接泄漏嫌疑：第 11 次请求 HTTP {r.status_code}: {r.text[:300]}"
        assert r.json().get("success") is True, \
            f"连接泄漏嫌疑：第 11 次 success=false: {r.json()}"

    def test_sourceDatabases_repeated(self, auth, base_url):
        """连续 5 次 /sync/sourceDatabases，结果一致且无连接泄漏。"""
        cookies = _auth_cookies(auth)
        url = f"{base_url}/sync/sourceDatabases"
        body = {
            "sourceHost": LOCAL_MYSQL["host"], "sourcePort": LOCAL_MYSQL["port"],
            "sourceUser": LOCAL_MYSQL["user"], "sourcePassword": LOCAL_MYSQL["password"],
        }

        first_set = None
        for i in range(5):
            r = requests.post(url, json=body, cookies=cookies, timeout=20)
            assert r.status_code == 200, \
                f"第 {i + 1} 次 HTTP {r.status_code}: {r.text[:300]}"
            j = r.json()
            assert j.get("success") is True, f"第 {i + 1} 次 success=false: {j}"
            data = j["data"]
            assert isinstance(data, list), f"第 {i + 1} 次返回非数组: {data}"
            current = set(data)
            if first_set is None:
                first_set = current
            else:
                assert current == first_set, \
                    f"第 {i + 1} 次结果与首次不一致（差集: {current ^ first_set}）"

        # 系统库不应出现
        assert "information_schema" not in first_set, "BUG: information_schema 未排除"
        assert "mysql" not in first_set, "BUG: mysql 未排除"
        assert "performance_schema" not in first_set, "BUG: performance_schema 未排除"
        assert "sys" not in first_set, "BUG: sys 未排除"
        assert "geodrsync" not in first_set, "BUG: geodrsync 未排除"

        # 连接泄漏检测：第 6 次请求仍应正常
        r = requests.post(url, json=body, cookies=cookies, timeout=20)
        assert r.status_code == 200, \
            f"连接泄漏嫌疑：第 6 次请求 HTTP {r.status_code}"

    def test_mixed_concurrent_load(self, auth, base_url):
        """混合并发：5 个 /sync/status + 5 个 /sync/mappings + 5 个 /sync/db/list，全部应成功。"""
        cookies = _auth_cookies(auth)
        status_url = f"{base_url}/sync/status"
        mappings_url = f"{base_url}/sync/mappings"
        dblist_url = f"{base_url}/sync/db/list"
        dblist_body = {"page": 1, "pageSize": 5, "condition": {}}
        lock = threading.Lock()
        all_results = []

        def call_status(_idx):
            r = requests.get(status_url, cookies=cookies, timeout=15)
            return ("status", r.status_code, r.json())

        def call_mappings(_idx):
            r = requests.get(mappings_url, cookies=cookies, timeout=15)
            return ("mappings", r.status_code, r.json())

        def call_dblist(_idx):
            r = requests.post(dblist_url, json=dblist_body, cookies=cookies, timeout=15)
            return ("dblist", r.status_code, r.json())

        with ThreadPoolExecutor(max_workers=15) as ex:
            futures = []
            futures += [ex.submit(call_status, i) for i in range(5)]
            futures += [ex.submit(call_mappings, i) for i in range(5)]
            futures += [ex.submit(call_dblist, i) for i in range(5)]
            for f in as_completed(futures):
                with lock:
                    all_results.append(f.result())

        assert len(all_results) == 15, f"并发完成数异常: 期望 15 实际 {len(all_results)}"

        by_kind = {"status": [], "mappings": [], "dblist": []}
        for kind, status, body in all_results:
            by_kind[kind].append((status, body))

        # /sync/status: 裸结构，200 + 含 status 字段
        assert len(by_kind["status"]) == 5
        for status, body in by_kind["status"]:
            assert status == 200, f"/sync/status HTTP {status}: {body}"
            assert "status" in body, f"/sync/status 缺 status 字段: {body}"

        # /sync/mappings: Result 包装，200 + success=true
        assert len(by_kind["mappings"]) == 5
        for status, body in by_kind["mappings"]:
            assert status == 200, f"/sync/mappings HTTP {status}: {body}"
            assert body.get("success") is True, f"/sync/mappings success=false: {body}"

        # /sync/db/list: 裸 PageRespVO，200 + 含 results / total
        assert len(by_kind["dblist"]) == 5
        for status, body in by_kind["dblist"]:
            assert status == 200, f"/sync/db/list HTTP {status}: {body}"
            assert "results" in body, f"/sync/db/list 缺 results: {body}"
            assert "total" in body, f"/sync/db/list 缺 total: {body}"
            assert isinstance(body["results"], list)
