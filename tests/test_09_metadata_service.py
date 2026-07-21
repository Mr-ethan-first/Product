# -*- coding: utf-8 -*-
"""DatabaseMetadataService 核心功能集成测试（通过 API 间接验证）。

覆盖能力：
- getAllUserDatabases:  排除系统库（information_schema / mysql / performance_schema / sys / DRPlatform）
- listTables:           只返回 BASE TABLE，不含视图
- ensureTargetDatabase: 源库出现新库时，同步引擎在灾备端自动建库
- sanitizeDdlForPortability: 含 utf8mb4_0900_ai_ci 的 DDL 降级为 utf8mb4_general_ci
- tableExists / sourceTables: 创建表后接口能返回该表名

测试库：geo_test_meta（本地 127.0.0.1 为源、192.168.88.88 为灾备目标）
清理策略：模块级 autouse fixture 在测试前后分别 DROP 该库（本地 + 远程）。
"""
import time

import pytest

from conftest import (
    assert_ok,
    db_exec,
    db_query,
    db_query_one,
    wait_for_condition,
    LOCAL_MYSQL,
    REMOTE_MYSQL,
)

TEST_DB = "geo_test_meta"

SYSTEM_DATABASES = {
    "information_schema",
    "mysql",
    "performance_schema",
    "sys",
    "DRPlatform",
}


# ===================== 辅助函数 =====================

def _local_exec(sql, args=None, fetch=False):
    return db_exec(
        LOCAL_MYSQL["host"], LOCAL_MYSQL["port"],
        LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
        sql, args=args, fetch=fetch,
    )


def _remote_exec(sql, args=None, fetch=False):
    return db_exec(
        REMOTE_MYSQL["host"], REMOTE_MYSQL["port"],
        REMOTE_MYSQL["user"], REMOTE_MYSQL["password"],
        sql, args=args, fetch=fetch,
    )


def _local_query(sql, args=None):
    return db_query(
        LOCAL_MYSQL["host"], LOCAL_MYSQL["port"],
        LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
        sql, args=args,
    )


def _remote_query(sql, args=None):
    return db_query(
        REMOTE_MYSQL["host"], REMOTE_MYSQL["port"],
        REMOTE_MYSQL["user"], REMOTE_MYSQL["password"],
        sql, args=args,
    )


def _remote_one(sql, args=None):
    return db_query_one(
        REMOTE_MYSQL["host"], REMOTE_MYSQL["port"],
        REMOTE_MYSQL["user"], REMOTE_MYSQL["password"],
        sql, args=args,
    )


def _drop_test_db_everywhere():
    """在本地与远程分别 DROP geo_test_meta（幂等）。"""
    try:
        _local_exec(f"DROP DATABASE IF EXISTS `{TEST_DB}`")
    except Exception:
        pass
    try:
        _remote_exec(f"DROP DATABASE IF EXISTS `{TEST_DB}`")
    except Exception:
        pass


def _ensure_local_db():
    """在本地（源）创建 geo_test_meta，utf8mb4 默认字符集。"""
    _local_exec(
        f"CREATE DATABASE IF NOT EXISTS `{TEST_DB}` "
        f"DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
    )


def _source_databases_body(mysql_cfg):
    return {
        "sourceHost": mysql_cfg["host"],
        "sourcePort": mysql_cfg["port"],
        "sourceUser": mysql_cfg["user"],
        "sourcePassword": mysql_cfg["password"],
    }


def _source_tables_body(mysql_cfg, database):
    body = _source_databases_body(mysql_cfg)
    body["database"] = database
    return body


def _remote_db_exists(db_name):
    """远程是否存在指定库。"""
    row = _remote_one(
        "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = %s",
        args=(db_name,),
    )
    return row is not None


def _remote_table_collation(db_name, table_name):
    """远程指定表的表级排序规则（information_schema.TABLES.TABLE_COLLATION）。"""
    row = _remote_one(
        "SELECT TABLE_COLLATION FROM information_schema.TABLES "
        "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s",
        args=(db_name, table_name),
    )
    return row["table_collation"] if row else None


def _remote_column_collation(db_name, table_name, column_name):
    """远程指定列的排序规则。"""
    row = _remote_one(
        "SELECT COLLATION_NAME FROM information_schema.COLUMNS "
        "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s AND COLUMN_NAME = %s",
        args=(db_name, table_name, column_name),
    )
    return row["collation_name"] if row else None


def _remote_table_exists(db_name, table_name):
    """远程是否存在指定表。"""
    row = _remote_one(
        "SELECT TABLE_NAME FROM information_schema.TABLES "
        "WHERE TABLE_SCHEMA = %s AND TABLE_NAME = %s",
        args=(db_name, table_name),
    )
    return row is not None


# ===================== 模块级清理 fixture =====================

@pytest.fixture(autouse=True, scope="module")
def _cleanup_geo_test_meta():
    """测试前 + 测试后清理 geo_test_meta 库（本地 + 远程）。"""
    _drop_test_db_everywhere()
    # 等待同步引擎处理完 DROP（避免残留导致后续 CREATE 冲突）
    time.sleep(3)
    yield
    _drop_test_db_everywhere()
    # 清理后再等一轮，确保同步引擎不会把残留重新同步回来
    time.sleep(3)


# ===================== 测试类 =====================

class TestMetadataService:
    """DatabaseMetadataService 核心功能（通过 /sync/sourceDatabases、/sync/sourceTables 间接验证）。"""

    # ------------------------------------------------------------------
    # 1. getAllUserDatabases —— 排除系统库
    # ------------------------------------------------------------------
    def test_list_user_databases(self, auth, base_url):
        """/sync/sourceDatabases 返回列表不应包含任何系统库。"""
        r = auth.post(
            f"{base_url}/sync/sourceDatabases",
            json=_source_databases_body(LOCAL_MYSQL),
            timeout=120,
        )
        j = assert_ok(r)
        data = j["data"]
        assert isinstance(data, list), f"返回非列表: {data}"

        for sys_db in SYSTEM_DATABASES:
            assert sys_db not in data, f"BUG: 系统库 {sys_db} 未被排除: {data}"

    # ------------------------------------------------------------------
    # 2. listTables —— 只返回 BASE TABLE，不含视图
    # ------------------------------------------------------------------
    def test_list_tables(self, auth, base_url):
        """创建含表 + 视图的库，/sync/sourceTables 应只返回 BASE TABLE。"""
        _ensure_local_db()

        # 创建两张基表
        _local_exec(
            f"CREATE TABLE IF NOT EXISTS `{TEST_DB}`.`t_meta_a` ("
            f"  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
            f"  val VARCHAR(64) DEFAULT NULL,"
            f"  PRIMARY KEY (id)"
            f") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        )
        _local_exec(
            f"CREATE TABLE IF NOT EXISTS `{TEST_DB}`.`t_meta_b` ("
            f"  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
            f"  ref BIGINT NOT NULL,"
            f"  PRIMARY KEY (id)"
            f") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        )
        # 创建视图
        _local_exec(
            f"CREATE OR REPLACE VIEW `{TEST_DB}`.`v_meta_join` AS "
            f"SELECT a.id, a.val, b.ref FROM `{TEST_DB}`.t_meta_a a "
            f"LEFT JOIN `{TEST_DB}`.t_meta_b b ON a.id = b.ref"
        )

        r = auth.post(
            f"{base_url}/sync/sourceTables",
            json=_source_tables_body(LOCAL_MYSQL, TEST_DB),
            timeout=120,
        )
        j = assert_ok(r)
        tables = j["data"]
        assert isinstance(tables, list), f"返回非列表: {tables}"

        # 基表应出现
        assert "t_meta_a" in tables, f"基表 t_meta_a 未返回: {tables}"
        assert "t_meta_b" in tables, f"基表 t_meta_b 未返回: {tables}"
        # 视图不应出现（listTables 只查 TABLE_TYPE='BASE TABLE'）
        assert "v_meta_join" not in tables, f"BUG: 视图 v_meta_join 不应返回: {tables}"

    # ------------------------------------------------------------------
    # 3. ensureTargetDatabase —— 源库出现新库时灾备端自动建库
    # ------------------------------------------------------------------
    def test_ensure_target_database(self, auth, base_url):
        """在源端创建 geo_test_meta 后，同步引擎应通过 ensureTargetDatabase 在灾备端建库。"""
        _ensure_local_db()

        # 等待同步引擎（poll-interval-ms=2000）扫描到新库并在灾备端建库
        existed = wait_for_condition(
            lambda: True if _remote_db_exists(TEST_DB) else None,
            timeout=180, interval=3,
            desc=f"灾备端出现 {TEST_DB} 库",
        )
        assert existed, f"灾备端未在 60s 内出现 {TEST_DB} 库，ensureTargetDatabase 可能未生效"

    # ------------------------------------------------------------------
    # 4. sanitizeDdlForPortability —— utf8mb4_0900_ai_ci 降级为 utf8mb4_general_ci
    # ------------------------------------------------------------------
    def test_ddl_portability(self, auth, base_url):
        """含 utf8mb4_0900_ai_ci 的表同步后，灾备端表应为 utf8mb4_general_ci。"""
        _ensure_local_db()

        table_name = "t_ddl_port"
        # 在源端创建使用 MySQL 8.0 专有排序规则的表（表级 + 列级）
        _local_exec(
            f"CREATE TABLE IF NOT EXISTS `{TEST_DB}`.`{table_name}` ("
            f"  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
            f"  name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,"
            f"  PRIMARY KEY (id)"
            f") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE utf8mb4_0900_ai_ci"
        )

        # 等待同步引擎在灾备端创建该表
        existed = wait_for_condition(
            lambda: True if _remote_table_exists(TEST_DB, table_name) else None,
            timeout=180, interval=3,
            desc=f"灾备端出现表 {TEST_DB}.{table_name}",
        )
        assert existed, f"灾备端未在 60s 内出现 {TEST_DB}.{table_name}，DDL 同步可能失败"

        # 验证表级排序规则已降级为 utf8mb4_general_ci
        table_coll = _remote_table_collation(TEST_DB, table_name)
        assert table_coll is not None, \
            f"无法读取灾备端 {TEST_DB}.{table_name} 的 TABLE_COLLATION"
        assert table_coll == "utf8mb4_general_ci", \
            f"BUG: 表级排序规则应为 utf8mb4_general_ci（降级后），实际为 {table_coll}"

        # 验证列级排序规则也已降级
        col_coll = _remote_column_collation(TEST_DB, table_name, "name")
        assert col_coll is not None, \
            f"无法读取灾备端 {TEST_DB}.{table_name}.name 的 COLLATION_NAME"
        assert col_coll == "utf8mb4_general_ci", \
            f"BUG: 列级排序规则应为 utf8mb4_general_ci（降级后），实际为 {col_coll}"

    # ------------------------------------------------------------------
    # 5. tableExists / sourceTables —— 创建表后接口能返回该表名
    # ------------------------------------------------------------------
    def test_table_exists_check(self, auth, base_url):
        """创建表后 /sync/sourceTables 应返回该表名。"""
        _ensure_local_db()

        table_name = "t_exists_chk"
        _local_exec(
            f"CREATE TABLE IF NOT EXISTS `{TEST_DB}`.`{table_name}` ("
            f"  id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
            f"  code VARCHAR(32) NOT NULL,"
            f"  PRIMARY KEY (id)"
            f") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        )

        r = auth.post(
            f"{base_url}/sync/sourceTables",
            json=_source_tables_body(LOCAL_MYSQL, TEST_DB),
            timeout=120,
        )
        j = assert_ok(r)
        tables = j["data"]
        assert isinstance(tables, list), f"返回非列表: {tables}"
        assert table_name in tables, \
            f"创建表 {table_name} 后 /sync/sourceTables 未返回该表: {tables}"
