# -*- coding: utf-8 -*-
"""HA 切换与重启任务管理测试。

验证管控元数据表（dbha_switch_status / sync_restart_task / sys_user）的
存在性、可查询性以及 SysUserInitializer 的 admin 账号自动初始化行为。

涉及表（均位于 geodrsync 元数据库）：
- dbha_switch_status   主备切换状态记录（DBHASwitchStatusJob 每 15s 扫描）
- sync_restart_task    同步重启任务记录（CREATE/DROP 操作由 RestartTaskManager 登记）
- sys_user             后台管理用户（SysUserInitializer @PostConstruct 自动建表 + 种子 admin）
"""
import pytest

from conftest import *  # noqa: F401,F403  assert_ok / assert_err / LOCAL_MYSQL / REMOTE_MYSQL / db_query / db_query_one ...


class TestDbhaSwitchStatus:
    """主备切换状态表 dbha_switch_status。"""

    def test_dbha_switch_status_table_exists(self):
        """dbha_switch_status 表存在且可查询（COUNT 不报错）。"""
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT COUNT(*) AS cnt FROM geodrsync.dbha_switch_status",
        )
        assert isinstance(rows, list), f"查询失败: {rows}"
        assert len(rows) == 1, f"COUNT 应返回 1 行: {rows}"
        assert rows[0]["cnt"] >= 0, f"行数应为非负: {rows[0]}"

    def test_dbha_switch_no_records(self):
        """无切换时表为空或仅含历史记录（本测试验证表可查询、字段完整）。

        DBHASwitchStatusJob 每 15s 扫描该表，switchAutoResync=false（默认关闭），
        不会自动触发 resync。测试环境通常无切换记录。
        """
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT id, virtual_ip, old_main_ip, old_standby_ip, main_ip, standby_ip, "
            "switch_time, source_db_name, source_binlog_file, source_binlog_pos, "
            "create_time, update_time "
            "FROM geodrsync.dbha_switch_status LIMIT 10",
        )
        assert isinstance(rows, list), f"查询失败: {rows}"
        # 不强制为空（历史切换可能留下记录），但字段完整性校验
        for row in rows:
            assert "id" in row, f"缺 id 字段: {row}"
            assert "virtual_ip" in row, f"缺 virtual_ip 字段: {row}"
            assert "main_ip" in row, f"缺 main_ip 字段: {row}"
            assert "standby_ip" in row, f"缺 standby_ip 字段: {row}"
            assert "source_db_name" in row, f"缺 source_db_name 字段: {row}"
            assert "create_time" in row, f"缺 create_time 字段: {row}"
            assert "update_time" in row, f"缺 update_time 字段: {row}"

    def test_dbha_switch_status_unique_index(self):
        """验证 dbha_switch_status 表的唯一索引 IDX_VIRTUAL_IP_DB 存在。

        通过尝试查询索引信息确认表结构完整（不真正插入数据以避免副作用）。
        """
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT INDEX_NAME, COLUMN_NAME "
            "FROM information_schema.STATISTICS "
            "WHERE TABLE_SCHEMA = 'geodrsync' AND TABLE_NAME = 'dbha_switch_status' "
            "AND INDEX_NAME = 'IDX_VIRTUAL_IP_DB' "
            "ORDER BY SEQ_IN_INDEX",
        )
        assert isinstance(rows, list), f"查询索引失败: {rows}"
        assert len(rows) >= 2, \
            f"IDX_VIRTUAL_IP_DB 唯一索引应含至少 2 列(virtual_ip + source_db_name): {rows}"
        index_cols = [r["column_name"].lower() for r in rows]
        assert "virtual_ip" in index_cols, f"索引缺 virtual_ip: {index_cols}"
        assert "source_db_name" in index_cols, f"索引缺 source_db_name: {index_cols}"


class TestRestartTask:
    """同步重启任务表 sync_restart_task。"""

    def test_restart_task_table_exists(self):
        """sync_restart_task 表存在且可查询（COUNT 不报错）。"""
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT COUNT(*) AS cnt FROM geodrsync.sync_restart_task",
        )
        assert isinstance(rows, list), f"查询失败: {rows}"
        assert len(rows) == 1, f"COUNT 应返回 1 行: {rows}"
        assert rows[0]["cnt"] >= 0, f"行数应为非负: {rows[0]}"

    def test_restart_task_records(self):
        """如有同步活动（CREATE/DROP），可能有重启任务记录。

        RestartTaskManager.recordTask() 在 CREATE/DROP 等需要全量刷新的操作时
        登记 sync_restart_task。本测试验证表结构可查询、字段完整。
        """
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT id, source_host, source_db, target_host, target_db, "
            "status, error_msg, create_time, update_time "
            "FROM geodrsync.sync_restart_task ORDER BY create_time DESC LIMIT 10",
        )
        assert isinstance(rows, list), f"查询失败: {rows}"
        # 字段完整性校验
        for row in rows:
            assert "id" in row, f"缺 id 字段: {row}"
            assert "source_host" in row, f"缺 source_host 字段: {row}"
            assert "source_db" in row, f"缺 source_db 字段: {row}"
            assert "target_host" in row, f"缺 target_host 字段: {row}"
            assert "target_db" in row, f"缺 target_db 字段: {row}"
            assert "status" in row, f"缺 status 字段: {row}"
            assert "create_time" in row, f"缺 create_time 字段: {row}"
            # status 取值校验：0-待处理 1-处理中 2-成功 3-失败
            status_val = row["status"]
            if status_val is not None:
                assert status_val in (0, 1, 2, 3), \
                    f"status 取值异常: {status_val} (应为 0/1/2/3): {row}"

    def test_restart_task_unique_index(self):
        """验证 sync_restart_task 表的唯一索引 IDX_UNIQUE_KEY 存在。"""
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT INDEX_NAME, COLUMN_NAME "
            "FROM information_schema.STATISTICS "
            "WHERE TABLE_SCHEMA = 'geodrsync' AND TABLE_NAME = 'sync_restart_task' "
            "AND INDEX_NAME = 'IDX_UNIQUE_KEY' "
            "ORDER BY SEQ_IN_INDEX",
        )
        assert isinstance(rows, list), f"查询索引失败: {rows}"
        assert len(rows) >= 4, \
            f"IDX_UNIQUE_KEY 唯一索引应含 4 列(source_host + source_db + target_host + target_db): {rows}"
        index_cols = [r["column_name"].lower() for r in rows]
        assert "source_host" in index_cols, f"索引缺 source_host: {index_cols}"
        assert "source_db" in index_cols, f"索引缺 source_db: {index_cols}"
        assert "target_host" in index_cols, f"索引缺 target_host: {index_cols}"
        assert "target_db" in index_cols, f"索引缺 target_db: {index_cols}"


class TestSysUserAutoInit:
    """SysUserInitializer 自动初始化 admin 账号。

    SysUserInitializer 在 @PostConstruct 中：
    1. 若 sys_user 表不存在则自动建表（CREATE TABLE IF NOT EXISTS）
    2. 若表为空则插入默认管理员 admin / admin123
    """

    def test_sys_user_auto_init(self):
        """sys_user 表存在且至少有一条记录（admin 账号由 SysUserInitializer 创建）。"""
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT id, username, password_hash, salt, create_time, update_time "
            "FROM geodrsync.sys_user",
        )
        assert isinstance(rows, list), f"查询 sys_user 失败: {rows}"
        assert len(rows) > 0, \
            "sys_user 表为空，SysUserInitializer 未初始化 admin 账号"

        # 验证 admin 账号存在
        usernames = {r["username"] for r in rows}
        assert "admin" in usernames, \
            f"admin 账号未找到（SysUserInitializer 未种子 admin）: {usernames}"

        # 验证密码哈希不为空、不是明文
        for r in rows:
            assert r["password_hash"], f"密码哈希为空: {r}"
            assert r["password_hash"] != "admin123", \
                f"BUG: 密码明文存储（password_hash == 'admin123'）: {r}"
            assert r["salt"], f"salt 为空: {r}"
            # password_hash 格式应为 salt:sha256Hex
            assert ":" in r["password_hash"] or len(r["password_hash"]) >= 32, \
                f"password_hash 格式异常（应含冒号或长度>=32）: {r}"

    def test_sys_user_table_structure(self):
        """验证 sys_user 表结构完整（含 username 唯一索引）。"""
        rows = db_query(
            LOCAL_MYSQL["host"], LOCAL_MYSQL["port"], LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
            "SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE "
            "FROM information_schema.STATISTICS "
            "WHERE TABLE_SCHEMA = 'geodrsync' AND TABLE_NAME = 'sys_user' "
            "ORDER BY INDEX_NAME, SEQ_IN_INDEX",
        )
        assert isinstance(rows, list), f"查询索引失败: {rows}"
        assert len(rows) > 0, f"sys_user 表无索引: {rows}"

        # 应存在 username 唯一索引
        index_names = {r["index_name"] for r in rows}
        assert len(index_names) > 0, f"无索引: {rows}"

        # 主键索引 + username 唯一索引（列名大小写因建表语句而异，用 .lower() 容错）
        has_unique_username = any(
            r["column_name"].lower() == "username" and r["non_unique"] == 0
            for r in rows
        )
        assert has_unique_username, \
            f"username 应有唯一索引: {rows}"

    def test_sys_user_admin_login_verified(self, anon, base_url):
        """通过登录验证 admin 账号可用（SysUserInitializer 创建的账号功能完整）。"""
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
        j = assert_ok(r)
        assert j["data"]["username"] == "admin", f"登录返回用户名异常: {j}"
