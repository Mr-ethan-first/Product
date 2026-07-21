# -*- coding: utf-8 -*-
"""操作日志页面功能自动化测试。

测试覆盖：
1. 模拟数据批量插入（>1 页 = >20 条），测试分页
2. /operation-log/list 分页查询（第 1 页、第 2 页、页码边界）
3. /operation-log/list 多条件搜索（用户名、客户端 IP、操作类型、结果、时间段）
4. /operation-log/types 操作类型枚举
5. 组合条件搜索（用户名 + IP + 时间段）
6. 空结果搜索
7. 详情查询（单条日志完整字段）

设计原则：
- 模拟数据通过直连 MySQL 批量插入，覆盖多种操作类型和 IP
- 每条模拟数据带唯一标记（operation_desc 包含 test_15 标识），测试后精确清理
- 不依赖其他测试产生的日志，独立可复现
"""
import time
import uuid
import json
import pytest
import requests
import pymysql
from datetime import datetime, timedelta

from conftest import (
    BASE_URL, ADMIN_USER, ADMIN_PASS, LOCAL_MYSQL,
    assert_ok, assert_err, db_exec, db_query, db_query_one,
    wait_for_condition,
)

GEODRSYNC_DB = {"host": "127.0.0.1", "port": 3306, "user": "root", "password": "123456", "database": "geodrsync"}

# 模拟数据标记，用于测试后精确清理
TEST_TAG = f"test15_{int(time.time())}_{uuid.uuid4().hex[:6]}"

# 模拟数据配置：30 条（>1 页，pageSize=20）
MOCK_COUNT = 30

# 操作类型和 IP 的组合
MOCK_DATA_TEMPLATE = [
    # (operation_type, result_status, client_ip, operation_desc_suffix)
    ("LOGIN", "SUCCESS", "192.168.1.10", "用户登录"),
    ("LOGIN", "FAILURE", "192.168.1.10", "登录失败-密码错误"),
    ("LOGOUT", "SUCCESS", "192.168.1.10", "用户登出"),
    ("REGISTER", "SUCCESS", "10.0.0.5", "新用户注册"),
    ("MAPPING_ADD", "SUCCESS", "172.16.0.100", "新增同步映射"),
    ("MAPPING_ADD", "FAILURE", "172.16.0.100", "新增映射失败-连接超时"),
    ("MAPPING_REMOVE", "SUCCESS", "172.16.0.100", "移除同步映射"),
    ("MAPPING_UPDATE", "SUCCESS", "172.16.0.100", "更新映射配置"),
    ("MAPPING_RELOAD", "SUCCESS", "192.168.1.50", "重载所有配置"),
    ("TEST_CONNECTION", "SUCCESS", "192.168.1.50", "测试源端连接"),
    ("TEST_CONNECTION", "FAILURE", "192.168.1.50", "测试目标端连接失败"),
    ("RESYNC", "SUCCESS", "10.0.0.20", "触发全量重同步"),
    ("RESYNC", "FAILURE", "10.0.0.20", "重同步失败-表不存在"),
]


# ===================== 辅助函数 =====================

def insert_mock_logs(count=MOCK_COUNT):
    """批量插入模拟操作日志，返回插入的 ID 列表。"""
    now = datetime.now()
    inserted_ids = []

    conn = pymysql.connect(**{k: v for k, v in GEODRSYNC_DB.items() if k != "database"})
    conn.select_db("geodrsync")
    try:
        with conn.cursor() as cur:
            for i in range(count):
                template = MOCK_DATA_TEMPLATE[i % len(MOCK_DATA_TEMPLATE)]
                op_type, result, ip, desc = template
                # 每条数据时间错开，便于时间段测试
                created = now - timedelta(seconds=i * 30)
                username = "admin" if i % 3 != 0 else f"user_{i % 3}"
                user_id = 1 if username == "admin" else None
                duration = 50 + i * 10

                sql = """INSERT INTO operation_log
                    (user_id, username, operation_type, operation_desc, target_resource,
                     request_url, request_method, request_params, result_status, error_msg,
                     client_ip, duration_ms, create_time)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)"""
                args = (
                    user_id, username, op_type, f"[{TEST_TAG}] {desc}",
                    f"db_table_{i}", f"/api/test/{i}", "POST",
                    json.dumps({"key": f"val_{i}", "password": "***"}, ensure_ascii=False),
                    result,
                    f"error detail {i}" if result == "FAILURE" else None,
                    ip, duration, created,
                )
                cur.execute(sql, args)
                inserted_ids.append(cur.lastrowid)
        conn.commit()
    finally:
        conn.close()

    return inserted_ids


def cleanup_mock_logs():
    """清理本次测试插入的模拟数据。"""
    db_exec(**GEODRSYNC_DB,
            sql="DELETE FROM operation_log WHERE operation_desc LIKE %s",
            args=[f"%[{TEST_TAG}]%"])


def query_log_list(session, body):
    """调用 /operation-log/list 接口。"""
    return session.post(f"{BASE_URL}/operation-log/list", json=body, timeout=10)


def query_log_types(session):
    """调用 /operation-log/types 接口。"""
    return session.get(f"{BASE_URL}/operation-log/types", timeout=10)


# ===================== 测试夹具 =====================

@pytest.fixture(scope="module")
def mock_data():
    """模块级夹具：插入模拟数据，模块结束后清理。"""
    ids = insert_mock_logs()
    yield ids
    cleanup_mock_logs()


# ===================== 测试类：操作日志列表与分页 =====================

class TestOperationLogList:
    """操作日志列表查询与分页测试。"""

    def test_01_types_endpoint(self, auth):
        """/operation-log/types 返回操作类型枚举列表。"""
        r = query_log_types(auth)
        assert_ok(r)
        data = r.json()["data"]
        types = data.get("types", data) if isinstance(data, dict) else data
        assert isinstance(types, list), f"types 应为列表: {data}"
        assert len(types) > 0, f"操作类型列表为空: {data}"
        # 验证包含常见类型
        type_str = "|".join(types)
        assert "LOGIN" in type_str, f"缺少 LOGIN 类型: {types}"

    def test_02_list_default_page(self, auth, mock_data):
        """默认查询返回第 1 页数据，不超过 pageSize 条。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 20})
        assert_ok(r)
        data = r.json()["data"]
        results = data["results"]
        total = data["total"]
        assert total >= MOCK_COUNT, f"总数应 >= {MOCK_COUNT}，实际 {total}"
        assert len(results) <= 20, f"第 1 页不应超过 20 条，实际 {len(results)}"

    def test_03_pagination_page1(self, auth, mock_data):
        """第 1 页返回 20 条（模拟数据 > 20 条）。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 20})
        assert_ok(r)
        data = r.json()["data"]
        assert len(data["results"]) == 20, f"第 1 页应有 20 条，实际 {len(data['results'])}"
        assert data["total"] >= MOCK_COUNT

    def test_04_pagination_page2(self, auth, mock_data):
        """第 2 页返回剩余数据（>0 条）。"""
        r = query_log_list(auth, {"page": 2, "pageSize": 20})
        assert_ok(r)
        data = r.json()["data"]
        assert len(data["results"]) > 0, f"第 2 页应有数据，实际 {len(data['results'])} 条"
        assert len(data["results"]) <= 20, f"第 2 页不应超过 20 条"

    def test_05_pagination_page1_and_page2_disjoint(self, auth, mock_data):
        """第 1 页和第 2 页的数据不重叠（ID 不重复）。"""
        r1 = query_log_list(auth, {"page": 1, "pageSize": 20})
        r2 = query_log_list(auth, {"page": 2, "pageSize": 20})
        ids1 = {row["id"] for row in r1.json()["data"]["results"]}
        ids2 = {row["id"] for row in r2.json()["data"]["results"]}
        overlap = ids1 & ids2
        assert len(overlap) == 0, f"第 1 页和第 2 页 ID 重叠: {overlap}"

    def test_06_pagination_beyond_last_page(self, auth, mock_data):
        """超出最后一页返回空列表。"""
        r = query_log_list(auth, {"page": 999, "pageSize": 20})
        assert_ok(r)
        data = r.json()["data"]
        assert len(data["results"]) == 0, f"超出最后一页应返回空列表"
        assert data["total"] >= MOCK_COUNT

    def test_07_custom_page_size(self, auth, mock_data):
        """自定义 pageSize=5，验证分页粒度。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 5})
        assert_ok(r)
        data = r.json()["data"]
        assert len(data["results"]) == 5, f"pageSize=5 应返回 5 条，实际 {len(data['results'])}"


# ===================== 测试类：搜索条件 =====================

class TestOperationLogSearch:
    """操作日志多条件搜索测试。"""

    def test_08_filter_by_username(self, auth, mock_data):
        """按用户名精确搜索。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "username": "admin"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "用户名 admin 应有日志"
        for row in results:
            assert row["username"] == "admin", f"过滤失败: {row['username']}"

    def test_09_filter_by_client_ip(self, auth, mock_data):
        """按客户端 IP 模糊搜索。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "clientIp": "192.168.1"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "IP 192.168.1.* 应有日志"
        for row in results:
            assert "192.168.1" in (row["clientIp"] or ""), f"IP 过滤失败: {row['clientIp']}"

    def test_10_filter_by_operation_type(self, auth, mock_data):
        """按操作类型搜索。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "operationType": "LOGIN"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "LOGIN 类型应有日志"
        for row in results:
            assert row["operationType"] == "LOGIN", f"类型过滤失败: {row['operationType']}"

    def test_11_filter_by_result_status_success(self, auth, mock_data):
        """按结果=成功搜索。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "resultStatus": "SUCCESS"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "应有 SUCCESS 日志"
        for row in results:
            assert row["resultStatus"] == "SUCCESS", f"结果过滤失败: {row['resultStatus']}"

    def test_12_filter_by_result_status_failure(self, auth, mock_data):
        """按结果=失败搜索。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "resultStatus": "FAILURE"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "应有 FAILURE 日志"
        for row in results:
            assert row["resultStatus"] == "FAILURE", f"结果过滤失败: {row['resultStatus']}"

    def test_13_filter_by_time_range(self, auth, mock_data):
        """按时间段搜索。"""
        now = datetime.now()
        start = (now - timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S")
        end = now.strftime("%Y-%m-%d %H:%M:%S")
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "startTime": start, "endTime": end})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "最近 1 小时应有日志"
        for row in results:
            assert row["createTime"] is not None

    def test_14_filter_by_time_range_future(self, auth, mock_data):
        """未来时间段搜索返回空结果。"""
        future_start = (datetime.now() + timedelta(days=365)).strftime("%Y-%m-%d %H:%M:%S")
        future_end = (datetime.now() + timedelta(days=365, hours=1)).strftime("%Y-%m-%d %H:%M:%S")
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "startTime": future_start, "endTime": future_end})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) == 0, f"未来时间段应返回空，实际 {len(results)} 条"

    def test_15_combined_filter_username_and_ip(self, auth, mock_data):
        """组合搜索：用户名 + IP。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "username": "admin", "clientIp": "192.168.1"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "admin + 192.168.1.* 应有日志"
        for row in results:
            assert row["username"] == "admin"
            assert "192.168.1" in (row["clientIp"] or "")

    def test_16_combined_filter_type_and_result(self, auth, mock_data):
        """组合搜索：操作类型 + 结果。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "operationType": "LOGIN", "resultStatus": "FAILURE"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0, "LOGIN + FAILURE 应有日志"
        for row in results:
            assert row["operationType"] == "LOGIN"
            assert row["resultStatus"] == "FAILURE"

    def test_17_combined_all_filters(self, auth, mock_data):
        """组合搜索：全部条件组合。"""
        now = datetime.now()
        start = (now - timedelta(hours=1)).strftime("%Y-%m-%d %H:%M:%S")
        end = now.strftime("%Y-%m-%d %H:%M:%S")
        r = query_log_list(auth, {
            "page": 1, "pageSize": 50,
            "username": "admin",
            "operationType": "LOGIN",
            "resultStatus": "SUCCESS",
            "clientIp": "192.168",
            "startTime": start,
            "endTime": end,
        })
        assert_ok(r)
        results = r.json()["data"]["results"]
        for row in results:
            assert row["username"] == "admin"
            assert row["operationType"] == "LOGIN"
            assert row["resultStatus"] == "SUCCESS"
            assert "192.168" in (row["clientIp"] or "")

    def test_18_filter_nonexistent_user(self, auth, mock_data):
        """搜索不存在的用户名返回空。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "username": f"nouser_{TEST_TAG}"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) == 0, f"不存在的用户应返回空，实际 {len(results)} 条"


# ===================== 测试类：详情与字段完整性 =====================

class TestOperationLogDetail:
    """操作日志详情与字段完整性测试。"""

    def test_19_log_record_fields(self, auth, mock_data):
        """每条日志记录包含完整字段。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 5})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0

        required_fields = ["id", "username", "operationType", "resultStatus",
                           "clientIp", "durationMs", "createTime"]
        for row in results:
            for field in required_fields:
                assert field in row, f"日志记录缺少字段 {field}: {row}"

    def test_20_log_ordering_desc(self, auth, mock_data):
        """日志按创建时间倒序排列。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 20})
        assert_ok(r)
        results = r.json()["data"]["results"]
        for i in range(1, len(results)):
            t_prev = results[i-1].get("createTime") or ""
            t_curr = results[i].get("createTime") or ""
            assert t_prev >= t_curr, \
                f"createTime 未按倒序排列: {t_prev} < {t_curr}"

    def test_21_password_masked_in_params(self, auth, mock_data):
        """请求参数中密码字段已脱敏。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50})
        assert_ok(r)
        results = r.json()["data"]["results"]
        for row in results:
            if row.get("requestParams"):
                params = row["requestParams"]
                # 不应包含明文密码
                assert "val_" not in params or "password" in params.lower(), \
                    f"参数可能未脱敏: {params[:100]}"
                # 如果包含 password 字段，应为 ***
                if "password" in params.lower():
                    assert "***" in params, f"密码未脱敏: {params[:100]}"

    def test_22_error_msg_present_on_failure(self, auth, mock_data):
        """失败日志包含 error_msg。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50, "resultStatus": "FAILURE"})
        assert_ok(r)
        results = r.json()["data"]["results"]
        assert len(results) > 0
        for row in results:
            assert row.get("errorMsg") is not None and row["errorMsg"] != "", \
                f"失败日志缺少 error_msg: {row}"

    def test_23_duration_ms_present(self, auth, mock_data):
        """日志记录包含耗时字段。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 50})
        assert_ok(r)
        results = r.json()["data"]["results"]
        has_duration = any(row.get("durationMs") is not None for row in results)
        assert has_duration, "应有包含 durationMs 的日志记录"


# ===================== 测试类：分页边界与自定义 pageSize =====================

class TestOperationLogPaginationEdge:
    """分页边界条件测试。"""

    def test_24_page_size_1(self, auth, mock_data):
        """pageSize=1 只返回 1 条。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 1})
        assert_ok(r)
        assert len(r.json()["data"]["results"]) == 1

    def test_25_page_size_100(self, auth, mock_data):
        """pageSize=100 返回所有模拟数据。"""
        r = query_log_list(auth, {"page": 1, "pageSize": 100})
        assert_ok(r)
        data = r.json()["data"]
        assert data["total"] >= MOCK_COUNT
        assert len(data["results"]) >= MOCK_COUNT

    def test_26_total_count_consistent(self, auth, mock_data):
        """不同页码的 total 一致。"""
        r1 = query_log_list(auth, {"page": 1, "pageSize": 20})
        r2 = query_log_list(auth, {"page": 2, "pageSize": 20})
        total1 = r1.json()["data"]["total"]
        total2 = r2.json()["data"]["total"]
        assert total1 == total2, f"不同页码 total 不一致: {total1} vs {total2}"

    def test_27_page_zero_returns_first_page(self, auth, mock_data):
        """page=0 不报错（降级为第 1 页或空）。"""
        r = query_log_list(auth, {"page": 0, "pageSize": 20})
        assert r.status_code == 200, f"page=0 应返回 200，实际 {r.status_code}"
