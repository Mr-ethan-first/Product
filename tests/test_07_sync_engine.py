# -*- coding: utf-8 -*-
"""同步引擎端到端自动化测试。

验证 GeoDRSync 内嵌同步引擎的核心能力：
- 数据同步（INSERT / UPDATE / DELETE 全链路）
- DDL 变更检测（ALTER TABLE 自动重建目标表）
- 删除对账（reconcileDeletes：源端删除 → 目标端同步删除）
- 全量同步（无 update_time 列的表，每轮全量重扫）
- 增量同步（有 update_time 列的表，基于水位增量）
- 新增表 / 删除表自动纳管

环境依赖：
- 本地 MySQL 127.0.0.1:3306 (root/123456) 作为生产中心
- 灾备 MySQL 192.168.88.88:3306 (root/123456) 作为灾备中心
- 后端 API http://127.0.0.1:8080（admin/admin123）
- 默认映射 127.0.0.1 -> 192.168.88.88 已在 application.yml 中配置
- 同步引擎 pollIntervalMs=2000（2 秒一轮）

执行顺序：pytest 默认按文件中定义顺序执行，TestSyncEngineE2E 的用例存在前后依赖。
"""
import pytest
from conftest import *

# 测试目标库名
DB_NAME = "geo_test_e2e"
# 简化连接参数访问
_L = LOCAL_MYSQL
_R = REMOTE_MYSQL


# ===================== 模块级夹具：清理残留 + 收尾 =====================

@pytest.fixture(scope="module", autouse=True)
def _module_db_cleanup():
    """模块开始前 / 结束后清理 geo_test_e2e（本地 + 灾备），避免残留干扰。"""
    drop_database(_L["host"], _L["port"], _L["user"], _L["password"], DB_NAME)
    drop_database(_R["host"], _R["port"], _R["user"], _R["password"], DB_NAME)
    # 等一轮让同步引擎感知到旧库被删除
    wait_for_sync(cycles=1, extra_ms=500)
    # 等待同步引擎完成至少一轮全量同步（28个库含 day02 的 250 万行，首轮较慢）
    # 通过检查 sync_progress 记录数来判断引擎是否已扫描完所有库
    import requests as _req
    _sess = _req.Session()
    _sess.headers.update({"Content-Type": "application/json"})
    _r = _sess.post("http://127.0.0.1:8080/auth/login",
                    json={"username": "admin", "password": "admin123"}, timeout=10)
    if _r.status_code == 200:
        _deadline = time.time() + 120
        while time.time() < _deadline:
            try:
                _r2 = _sess.post("http://127.0.0.1:8080/sync/db/list",
                                 json={"page": 1, "pageSize": 200}, timeout=15)
                if _r2.status_code == 200:
                    _j = _r2.json()
                    _total = _j.get("total", 0)
                    # 当进度记录数 >= 20 时，说明首轮已扫描大部分库
                    if _total >= 20:
                        print(f"  同步引擎就绪: {_total} 条进度记录")
                        break
            except Exception:
                pass
            time.sleep(3)
    yield
    # 模块全部用例结束后再次清理，保持环境干净
    drop_database(_L["host"], _L["port"], _L["user"], _L["password"], DB_NAME)
    drop_database(_R["host"], _R["port"], _R["user"], _R["password"], DB_NAME)


# ===================== 远程查询辅助函数 =====================

def _local_exec(sql, args=None, fetch=False):
    """在本地源端 geo_test_e2e 库执行 SQL。"""
    return db_exec(_L["host"], _L["port"], _L["user"], _L["password"],
                   sql, args=args, database=DB_NAME, fetch=fetch)


def _local_query(sql, args=None):
    """查询本地源端 geo_test_e2e 库。"""
    return _local_exec(sql, args=args, fetch=True)


def _remote_query(sql, args=None):
    """查询灾备端 geo_test_e2e 库。"""
    return db_query(_R["host"], _R["port"], _R["user"], _R["password"],
                    sql, args=args, database=DB_NAME)


def _remote_query_one(sql, args=None):
    """查询单行灾备端数据。"""
    return db_query_one(_R["host"], _R["port"], _R["user"], _R["password"],
                        sql, args=args, database=DB_NAME)


def _remote_count(table, where=""):
    """统计灾备端指定表的行数。"""
    sql = f"SELECT COUNT(*) AS c FROM `{table}`"
    if where:
        sql += f" WHERE {where}"
    row = _remote_query_one(sql)
    return (row or {}).get("c", 0)


def _remote_table_exists(table):
    """灾备端是否存在指定表。"""
    rows = db_query(_R["host"], _R["port"], _R["user"], _R["password"],
                    "SELECT TABLE_NAME FROM information_schema.TABLES "
                    "WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s",
                    args=(DB_NAME, table))
    return len(rows) > 0


def _remote_has_column(table, column):
    """灾备端指定表是否包含指定列。"""
    rows = db_query(_R["host"], _R["port"], _R["user"], _R["password"],
                    "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    "WHERE TABLE_SCHEMA=%s AND TABLE_NAME=%s AND COLUMN_NAME=%s",
                    args=(DB_NAME, table, column))
    return len(rows) > 0


def _find_progress(auth, base_url, db_name=DB_NAME):
    """从同步进度列表中查找指定库的进度记录。"""
    results, _ = get_sync_progress(auth, base_url, source_db=db_name)
    for row in results:
        if row.get("sourceDbName") == db_name:
            return row
    # 退化方案：服务端可能未按 sourceDbName 精确过滤，客户端再筛一遍
    results, _ = get_sync_progress(auth, base_url)
    for row in results:
        if row.get("sourceDbName") == db_name:
            return row
    return None


# ===================== 端到端同步验证 =====================

class TestSyncEngineE2E:
    """端到端同步：建库建表 → 插入 → 更新 → 删除 → DDL → 新表 → 删表。

    用例间存在前后依赖，需按定义顺序执行（pytest 默认行为）。
    """

    def test_data_sync_insert(self, auth, base_url):
        """1) 创建 geo_test_e2e + t_users，插入 3 行，验证灾备端同步到位。"""
        # 创建源端数据库
        ensure_database(_L["host"], _L["port"], _L["user"], _L["password"], DB_NAME)
        # 建表（含 update_time 列，走增量同步）
        _local_exec(
            "CREATE TABLE IF NOT EXISTS t_users ("
            "  id INT PRIMARY KEY,"
            "  name VARCHAR(100),"
            "  age INT,"
            "  update_time TIMESTAMP"
            ")"
        )
        # 插入 3 行
        _local_exec(
            "INSERT INTO t_users (id, name, age, update_time) VALUES "
            "(1, 'alice', 20, NOW()),"
            "(2, 'bob',   25, NOW()),"
            "(3, 'charlie', 30, NOW())"
        )

        # 等待同步引擎完成（建库 + 建表 + 全量同步 + 删除对账）
        wait_for_condition(
            lambda: _remote_count("t_users") == 3,
            timeout=180, interval=2, desc="灾备端 t_users 同步到 3 行"
        )

        rows = _remote_query("SELECT * FROM t_users ORDER BY id")
        assert len(rows) == 3, f"灾备端应有 3 行，实际 {len(rows)}: {rows}"
        assert rows[0]["name"] == "alice", f"id=1 name 不符: {rows[0]}"
        assert rows[0]["age"] == 20, f"id=1 age 不符: {rows[0]}"
        assert rows[1]["name"] == "bob", f"id=2 name 不符: {rows[1]}"
        assert rows[2]["name"] == "charlie", f"id=3 name 不符: {rows[2]}"

    def test_data_sync_update(self, auth, base_url):
        """2) 修改源端 id=1 的 name 与 age，验证灾备端对应行已更新。"""
        _local_exec(
            "UPDATE t_users SET name=%s, age=%s, update_time=NOW() WHERE id=%s",
            args=("alice_updated", 21, 1)
        )

        # 增量同步：update_time 推进 → 增量扫描捕获
        wait_for_condition(
            lambda: (_remote_query_one(
                "SELECT name, age FROM t_users WHERE id=1"
            ) or {}).get("name") == "alice_updated",
            timeout=180, interval=2, desc="灾备端 id=1 更新为 alice_updated"
        )

        row = _remote_query_one("SELECT name, age FROM t_users WHERE id=1")
        assert row is not None, "灾备端 id=1 不存在"
        assert row["name"] == "alice_updated", f"name 未更新: {row}"
        assert row["age"] == 21, f"age 未更新: {row}"
        # 其余行不应被改动
        assert _remote_count("t_users") == 3, "更新操作不应改变总行数"

    def test_data_sync_delete(self, auth, base_url):
        """3) 删除源端 id=3 一行，等待 reconcileDeletes，验证灾备端对应行也被删除。"""
        _local_exec("DELETE FROM t_users WHERE id=3")

        # 删除对账每轮执行，但跨主机网络延迟，给足 30s
        wait_for_condition(
            lambda: _remote_count("t_users", where="id=3") == 0,
            timeout=180, interval=2, desc="灾备端 id=3 被删除对账删除"
        )

        assert _remote_count("t_users") == 2, "删除后灾备端应剩 2 行"
        assert _remote_query_one("SELECT id FROM t_users WHERE id=3") is None, \
            "灾备端 id=3 不应再存在"

    def test_ddl_alter(self, auth, base_url):
        """4) 源端 ALTER TABLE ADD COLUMN email，等待重建，验证灾备端表也有 email 列。

        DDL 检测会 DROP + CREATE 重建目标表并重置水位，需要等待更多轮。
        """
        _local_exec(
            "ALTER TABLE t_users ADD COLUMN email VARCHAR(100)"
        )

        # DDL 检测 → 重建表 → 全量重扫，耗时更长，cycles=5
        wait_for_condition(
            lambda: _remote_has_column("t_users", "email"),
            timeout=180, interval=2, desc="灾备端 t_users 新增 email 列"
        )
        # 再等一轮让重建后的数据同步完成
        wait_for_sync(cycles=2)

        assert _remote_has_column("t_users", "email"), "灾备端缺 email 列"
        # 重建后数据应被重新全量同步（源端现有 2 行：id=1, id=2）
        assert _remote_count("t_users") == 2, "重建后灾备端应有 2 行数据"
        row = _remote_query_one("SELECT email FROM t_users WHERE id=1")
        assert row is not None, "重建后 id=1 数据丢失"
        assert row.get("email") is None, f"源端 email 为 NULL，灾备端应为 NULL: {row}"

    def test_new_table_sync(self, auth, base_url):
        """5) 源端新建 t_orders 表并插入数据，等待同步，验证灾备端也有该表和数据。"""
        _local_exec(
            "CREATE TABLE IF NOT EXISTS t_orders ("
            "  id INT PRIMARY KEY,"
            "  amount DECIMAL(10,2),"
            "  update_time TIMESTAMP"
            ")"
        )
        _local_exec(
            "INSERT INTO t_orders (id, amount, update_time) VALUES "
            "(1001, 99.50, NOW()),"
            "(1002, 128.00, NOW())"
        )

        wait_for_condition(
            lambda: _remote_table_exists("t_orders") and _remote_count("t_orders") == 2,
            timeout=180, interval=2, desc="灾备端 t_orders 同步到位（2 行）"
        )

        assert _remote_table_exists("t_orders"), "灾备端未建 t_orders 表"
        rows = _remote_query("SELECT * FROM t_orders ORDER BY id")
        assert len(rows) == 2, f"灾备端 t_orders 应有 2 行: {rows}"
        assert float(rows[0]["amount"]) == 99.50, f"id=1001 amount 不符: {rows[0]}"
        assert float(rows[1]["amount"]) == 128.00, f"id=1002 amount 不符: {rows[1]}"

    def test_drop_table_sync(self, auth, base_url):
        """6) 源端 DROP TABLE t_orders，等待同步，验证灾备端 t_orders 也被删除。"""
        _local_exec("DROP TABLE IF EXISTS t_orders")

        wait_for_condition(
            lambda: not _remote_table_exists("t_orders"),
            timeout=180, interval=2, desc="灾备端 t_orders 被删除"
        )
        assert not _remote_table_exists("t_orders"), "灾备端 t_orders 应已被删除"


# ===================== 同步进度验证 =====================

class TestSyncProgress:
    """验证同步进度表中 geo_test_e2e 的进度记录与状态。"""

    def test_progress_exists(self, auth, base_url):
        """7) geo_test_e2e 应在同步进度表中有记录。"""
        row = wait_for_condition(
            lambda: _find_progress(auth, base_url),
            timeout=180, interval=2, desc="同步进度表出现 geo_test_e2e 记录"
        )
        assert row is not None, "同步进度表中未找到 geo_test_e2e"
        assert row.get("sourceDbName") == DB_NAME, f"sourceDbName 不符: {row}"
        assert row.get("sourceIp") == _L["host"], f"sourceIp 不符: {row}"
        assert row.get("targetIp") == _R["host"], f"targetIp 不符: {row}"

    def test_progress_state_syncing(self, auth, base_url):
        """8) 同步中的进度 state 应为 2（SYNCING）。"""
        row = wait_for_condition(
            lambda: (lambda r: r if r and r.get("state") == 2 else None)(
                _find_progress(auth, base_url)
            ),
            timeout=180, interval=2, desc="geo_test_e2e 进度 state=2 同步中"
        )
        assert row is not None, "未找到 state=2 的 geo_test_e2e 进度记录"
        assert row.get("state") == 2, f"state 应为 2（同步中），实际: {row.get('state')}"


# ===================== 同步引擎健壮性 =====================

class TestSyncEngineResilience:
    """验证同步引擎对边界场景的处理：空表 / 无 update_time 列 / 特殊字符数据。"""

    def test_empty_table_sync(self, auth, base_url):
        """9) 创建空表（无数据），等待同步，验证灾备端表结构存在。"""
        _local_exec(
            "CREATE TABLE IF NOT EXISTS t_empty ("
            "  id INT PRIMARY KEY,"
            "  val VARCHAR(50)"
            ")"
        )
        # 空表：源端无数据，灾备端应仅创建表结构
        wait_for_condition(
            lambda: _remote_table_exists("t_empty"),
            timeout=180, interval=2, desc="灾备端 t_empty 表结构同步"
        )
        assert _remote_table_exists("t_empty"), "灾备端未创建空表 t_empty"
        assert _remote_count("t_empty") == 0, "空表灾备端行数应为 0"
        assert _remote_has_column("t_empty", "id"), "t_empty 缺 id 列"
        assert _remote_has_column("t_empty", "val"), "t_empty 缺 val 列"

    def test_no_update_time_column(self, auth, base_url):
        """10) 创建无 update_time 列的表，插入数据，验证被全量同步到灾备端。"""
        _local_exec(
            "CREATE TABLE IF NOT EXISTS t_no_upd ("
            "  id INT PRIMARY KEY,"
            "  name VARCHAR(100)"
            ")"
        )
        _local_exec(
            "INSERT INTO t_no_upd (id, name) VALUES "
            "(201, 'no_upd_one'),"
            "(202, 'no_upd_two')"
        )

        # 无 update_time 列 → 每轮全量重扫
        wait_for_condition(
            lambda: _remote_count("t_no_upd") == 2,
            timeout=180, interval=2, desc="灾备端 t_no_upd 全量同步 2 行"
        )
        rows = _remote_query("SELECT * FROM t_no_upd ORDER BY id")
        assert len(rows) == 2, f"灾备端 t_no_upd 应有 2 行: {rows}"
        assert rows[0]["name"] == "no_upd_one", f"id=201 name 不符: {rows[0]}"
        assert rows[1]["name"] == "no_upd_two", f"id=202 name 不符: {rows[1]}"

    def test_special_chars_in_data(self, auth, base_url):
        """11) 插入含特殊字符的数据（中文 / emoji / 引号），验证灾备端数据一致。"""
        _local_exec(
            "CREATE TABLE IF NOT EXISTS t_special ("
            "  id INT PRIMARY KEY,"
            "  content VARCHAR(255),"
            "  label VARCHAR(100),"
            "  update_time TIMESTAMP"
            ")"
        )
        # 使用参数化查询安全插入特殊字符
        cases = [
            (301, "你好世界", "中文测试"),
            (302, "🎉🚀💻🎊", "emoji测试"),
            (303, '含"双引号"内容', "双引号"),
            (304, "含'单引号'内容", "单引号"),
            (305, "换行\n制表\t结束", "控制字符"),
        ]
        for cid, content, label in cases:
            _local_exec(
                "INSERT INTO t_special (id, content, label, update_time) "
                "VALUES (%s, %s, %s, NOW())",
                args=(cid, content, label)
            )

        wait_for_condition(
            lambda: _remote_count("t_special") == len(cases),
            timeout=180, interval=2, desc=f"灾备端 t_special 同步到 {len(cases)} 行"
        )

        rows = _remote_query("SELECT * FROM t_special ORDER BY id")
        assert len(rows) == len(cases), f"行数不符: {rows}"
        by_id = {r["id"]: r for r in rows}
        for cid, content, label in cases:
            r = by_id[cid]
            assert r["content"] == content, \
                f"id={cid} content 不符: 期望 {content!r} 实际 {r['content']!r}"
            assert r["label"] == label, \
                f"id={cid} label 不符: 期望 {label!r} 实际 {r['label']!r}"
