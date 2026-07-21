# -*- coding: utf-8 -*-
"""后端定时任务（@Scheduled）集成测试。

验证以下定时任务在后台正常运行：
- SyncProgressJob (fixedDelay=10s):  内存进度缓存持久化到 sync_progress 表
- SourceBinlogJob  (fixedDelay=30s): 对每个源主机 SHOW MASTER STATUS，写入 source_latest_binlog_info
- ScanNewDatabaseJob (fixedDelay=60s): 扫描源主机新增用户库（仅日志输出，无数据库副作用）
- DBHASwitchStatusJob (fixedDelay=15s): 扫描 dbha_switch_status 表变化

测试策略：
- 直连 DRPlatform 元数据库验证定时任务的副作用（表记录存在、字段非空、update_time 新鲜）。
- 用 wait_for_condition 轮询等待定时任务跑过若干轮，避免启动时序导致的误报。
- 时间判定全部在 MySQL 侧用 NOW() / TIMESTAMPDIFF 计算，规避客户端与服务端时区差异。
"""
from conftest import (
    assert_ok,
    db_query,
    db_query_one,
    wait_for_condition,
    LOCAL_MYSQL,
    REMOTE_MYSQL,
)

META_DB = "DRPlatform"


def _meta(sql, args=None):
    """直查本地 DRPlatform 元数据库的快捷封装。"""
    return db_query(
        LOCAL_MYSQL["host"], LOCAL_MYSQL["port"],
        LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
        sql, args=args, database=META_DB,
    )


def _meta_one(sql, args=None):
    """直查本地 DRPlatform 元数据库（单行）。"""
    return db_query_one(
        LOCAL_MYSQL["host"], LOCAL_MYSQL["port"],
        LOCAL_MYSQL["user"], LOCAL_MYSQL["password"],
        sql, args=args, database=META_DB,
    )


class TestSyncProgressJob:
    """SyncProgressJob (fixedDelay=10s)：syncProgressService.persistFromMemory() → sync_progress 表。"""

    def test_progress_persisted_to_db(self):
        """后端启动后 sync_progress 表应有记录，且 source_ip / source_db_name / state 非空。"""

        def get_rows():
            rows = _meta(
                "SELECT source_ip, source_db_name, target_ip, target_db_name, state, update_time "
                "FROM sync_progress ORDER BY update_time DESC LIMIT 50"
            )
            return rows if rows else None

        rows = wait_for_condition(
            get_rows, timeout=60, interval=5,
            desc="sync_progress 表出现持久化记录",
        )
        assert rows, "sync_progress 表为空，SyncProgressJob 未持久化任何进度"
        for row in rows:
            assert row["source_ip"], f"source_ip 为空: {row}"
            assert row["source_db_name"], f"source_db_name 为空: {row}"
            assert row["state"] is not None, f"state 为空: {row}"

    def test_progress_updated_within_30s(self):
        """sync_progress.update_time 应在最近 30s 内被刷新（证明 10s 定时任务在运行）。"""

        def fresh_row():
            row = _meta_one(
                "SELECT update_time, "
                "TIMESTAMPDIFF(SECOND, update_time, NOW()) AS age "
                "FROM sync_progress ORDER BY update_time DESC LIMIT 1"
            )
            if not row or row["age"] is None:
                return None
            return row if row["age"] <= 30 else None

        row = wait_for_condition(
            fresh_row, timeout=90, interval=5,
            desc="sync_progress 记录 update_time 在最近 30s 内",
        )
        assert row is not None, \
            "90s 内未观察到 sync_progress.update_time 在最近 30s 内被刷新，SyncProgressJob 可能未运行"


class TestSourceBinlogJob:
    """SourceBinlogJob (fixedDelay=30s)：SHOW MASTER STATUS → source_latest_binlog_info 表（幂等 upsert）。"""

    def test_binlog_info_persisted(self):
        """source_latest_binlog_info 表应有记录，且 source_ip / source_binlog_file / source_binlog_pos 非空。"""

        def get_rows():
            rows = _meta(
                "SELECT source_ip, source_db_name, source_binlog_file, source_binlog_pos, "
                "source_binlog_time, update_time "
                "FROM source_latest_binlog_info ORDER BY update_time DESC LIMIT 50"
            )
            return rows if rows else None

        rows = wait_for_condition(
            get_rows, timeout=90, interval=5,
            desc="source_latest_binlog_info 表出现记录",
        )
        assert rows, "source_latest_binlog_info 表为空，SourceBinlogJob 未执行"
        for row in rows:
            assert row["source_ip"], f"source_ip 为空: {row}"
            assert row["source_binlog_file"], f"source_binlog_file 为空: {row}"
            assert row["source_binlog_pos"] is not None and row["source_binlog_pos"] > 0, \
                f"source_binlog_pos 异常: {row}"

    def test_binlog_info_updated_within_60s(self):
        """source_latest_binlog_info.update_time 应在最近 60s 内（证明 30s 定时任务在运行）。"""

        def fresh_row():
            row = _meta_one(
                "SELECT update_time, "
                "TIMESTAMPDIFF(SECOND, update_time, NOW()) AS age "
                "FROM source_latest_binlog_info ORDER BY update_time DESC LIMIT 1"
            )
            if not row or row["age"] is None:
                return None
            return row if row["age"] <= 60 else None

        row = wait_for_condition(
            fresh_row, timeout=120, interval=5,
            desc="source_latest_binlog_info update_time 在最近 60s 内",
        )
        assert row is not None, \
            "120s 内未观察到 source_latest_binlog_info 在最近 60s 内被刷新，SourceBinlogJob 可能未运行"


class TestDBHASwitchStatusJob:
    """DBHASwitchStatusJob (fixedDelay=15s)：扫描 dbha_switch_status 表变化。"""

    def test_ha_switch_table_accessible(self):
        """dbha_switch_status 表可查询（表存在，即使为空也 OK）。"""
        # 能查出列就说明表存在；行数为 0 也通过
        rows = _meta(
            "SELECT id, virtual_ip, old_main_ip, old_standby_ip, main_ip, standby_ip, "
            "switch_time, source_db_name, source_binlog_file, source_binlog_pos, "
            "create_time, update_time "
            "FROM dbha_switch_status LIMIT 50"
        )
        assert rows is not None, "dbha_switch_status 表查询失败（表可能不存在）"

    def test_ha_switch_empty_when_no_switch(self):
        """无主备切换发生时，dbha_switch_status 表应为空或无近期（120s 内）新增记录。"""
        summary = _meta_one(
            "SELECT COUNT(*) AS cnt FROM dbha_switch_status"
        )
        total = summary["cnt"] if summary else 0

        if total == 0:
            # 表为空，无切换发生 —— 符合预期
            return

        # 表非空（历史遗留），但测试期间不应有新切换记录
        fresh = _meta_one(
            "SELECT COUNT(*) AS fresh_cnt FROM dbha_switch_status "
            "WHERE TIMESTAMPDIFF(SECOND, update_time, NOW()) <= 120"
        )
        fresh_cnt = fresh["fresh_cnt"] if fresh else 0
        assert fresh_cnt == 0, \
            f"无主备切换期间不应有新记录，但发现 {fresh_cnt} 条 update_time 在 120s 内的切换记录"


class TestScanNewDatabaseJob:
    """ScanNewDatabaseJob (fixedDelay=60s)：扫描源主机新增用户库（仅日志，无数据库副作用）。

    该任务无直接数据库副作用，通过同步引擎总状态健康（NORMAL）间接验证后台任务未导致引擎异常。
    """

    def test_scan_job_runs_without_error(self, auth, base_url):
        """间接验证：/sync/status 返回 NORMAL，说明后台扫描任务未导致引擎异常。"""
        r = auth.get(f"{base_url}/sync/status", timeout=10)
        assert r.status_code == 200, f"/sync/status 请求失败: {r.status_code} {r.text[:300]}"
        j = r.json()
        assert "status" in j, f"响应缺 status 字段: {j}"
        assert j["status"] == "NORMAL", \
            f"同步引擎非 NORMAL 状态（后台任务可能异常）: {j}"
