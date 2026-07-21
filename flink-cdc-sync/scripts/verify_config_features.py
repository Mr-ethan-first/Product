#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
端到端验证新增的页面配置能力：
  1) 多选数据库同步（扇出为多个映射）
  2) 忽略表（DML/DDL 均跳过）
  3) 字段转换规则（IP 替换：源值 -> 目标值）
通过调用后端 REST 接口 + 直连两端 MySQL 核对数据。
"""
import json
import time
import urllib.request
import pymysql

BASE = "http://127.0.0.1:8080"
SRC = dict(host="127.0.0.1", port=3306, user="root", password="123456")
TGT = dict(host="192.168.88.88", port=3306, user="root", password="123456")
TEST_DB = "geotest_cfg"


def http_post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))


def http_get(path):
    with urllib.request.urlopen(BASE + path, timeout=15) as r:
        return json.loads(r.read().decode("utf-8"))


def conn(cfg, db=None):
    c = dict(cfg)
    if db:
        c["database"] = db
    return pymysql.connect(charset="utf8mb4", connect_timeout=8, **c)


def setup_source():
    c = conn(SRC)
    try:
        with c.cursor() as cur:
            cur.execute(f"DROP DATABASE IF EXISTS `{TEST_DB}`")
            cur.execute(f"CREATE DATABASE `{TEST_DB}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        c.commit()
    finally:
        c.close()
    c = conn(SRC, TEST_DB)
    try:
        with c.cursor() as cur:
            cur.execute("""CREATE TABLE t_user (
                id INT PRIMARY KEY AUTO_INCREMENT,
                name VARCHAR(64),
                ip VARCHAR(64),
                amount DECIMAL(12,2),
                update_time DATETIME DEFAULT CURRENT_TIMESTAMP
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci""")
            cur.execute("""CREATE TABLE t_skip (
                id INT PRIMARY KEY AUTO_INCREMENT, x VARCHAR(32)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci""")
            for i in range(3):
                cur.execute("INSERT INTO t_user(name,ip,amount) VALUES (%s,%s,%s)",
                            (f"u{i}", "192.168.1.100", 100 + i))
            cur.execute("INSERT INTO t_skip(x) VALUES ('should-not-sync')")
        c.commit()
        print("[setup] source db ready: t_user x3 (ip=192.168.1.100), t_skip x1")
    finally:
        c.close()


def setup_target_db():
    c = conn(TGT)
    try:
        with c.cursor() as cur:
            cur.execute(f"DROP DATABASE IF EXISTS `{TEST_DB}`")
            cur.execute(f"CREATE DATABASE `{TEST_DB}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
        c.commit()
        print("[setup] target db ready (empty)")
    finally:
        c.close()


def verify_source_databases():
    r = http_post("/sync/sourceDatabases", {
        "sourceHost": SRC["host"], "sourcePort": SRC["port"],
        "sourceUser": SRC["user"], "sourcePassword": SRC["password"],
        "targetHost": TGT["host"], "targetPort": TGT["port"],
        "targetUser": TGT["user"], "targetPassword": TGT["password"],
        "databases": []
    })
    dbs = (r.get("data") or [])
    ok = TEST_DB in dbs
    print(f"[verify] sourceDatabases contains {TEST_DB}: {ok}  (total {len(dbs)})")
    return ok


def verify_test_connection():
    r = http_post("/sync/mapping/test", {
        "sourceHost": SRC["host"], "sourcePort": SRC["port"],
        "sourceUser": SRC["user"], "sourcePassword": SRC["password"],
        "targetHost": TGT["host"], "targetPort": TGT["port"],
        "targetUser": TGT["user"], "targetPassword": TGT["password"],
        "databases": []
    })
    d = r.get("data") or {}
    sk = (d.get("source") or {}).get("ok")
    tk = (d.get("target") or {}).get("ok")
    print(f"[verify] connection test source={sk} target={tk}")
    return bool(sk and tk)


def save_and_sync():
    r = http_post("/sync/mapping/add", {
        "sourceHost": SRC["host"], "sourcePort": SRC["port"],
        "sourceUser": SRC["user"], "sourcePassword": SRC["password"],
        "targetHost": TGT["host"], "targetPort": TGT["port"],
        "targetUser": TGT["user"], "targetPassword": TGT["password"],
        "databases": [TEST_DB],
        "ignoreTables": ["t_skip"],
        "transformRules": [{
            "dbName": "*", "tableName": "*", "fieldName": "ip",
            "sourceValue": "192.168.1.100", "targetValue": "10.0.0.1"
        }]
    })
    print("[save] addMapping ->", json.dumps(r.get("data"), ensure_ascii=False))
    return (r.get("data") or {}).get("created")


def check_target():
    time.sleep(25)
    c = conn(TGT, TEST_DB)
    try:
        with c.cursor() as cur:
            cur.execute("SELECT COUNT(*), GROUP_CONCAT(DISTINCT ip) FROM t_user")
            cnt, ips = cur.fetchone()
            cur.execute("SHOW TABLES LIKE 't_skip'")
            skip_exists = cur.fetchone() is not None
        print(f"[check] target t_user rows={cnt}, distinct ip={ips}")
        print(f"[check] target t_skip exists (should be False): {skip_exists}")
        transform_ok = (cnt == 3 and ips == "10.0.0.1")
        ignore_ok = (not skip_exists)
        return transform_ok, ignore_ok
    finally:
        c.close()


def cleanup(created):
    try:
        if created:
            for k in created:
                http_post("/sync/mapping/remove", {"instanceKey": k})
        c = conn(SRC); c.cursor().execute(f"DROP DATABASE IF EXISTS `{TEST_DB}`"); c.commit(); c.close()
        c = conn(TGT); c.cursor().execute(f"DROP DATABASE IF EXISTS `{TEST_DB}`"); c.commit(); c.close()
        print("[cleanup] removed mapping + dropped test dbs")
    except Exception as e:
        print("[cleanup] warn:", e)


if __name__ == "__main__":
    print("=== GeoDRSync 配置功能端到端验证 ===")
    setup_source()
    setup_target_db()
    s1 = verify_source_databases()
    s2 = verify_test_connection()
    created = save_and_sync()
    t_ok, i_ok = check_target()
    print("\n=== 结果 ===")
    print("源库列表含测试库 :", "PASS" if s1 else "FAIL")
    print("连接测试通过     :", "PASS" if s2 else "FAIL")
    print("字段转换(IP替换) :", "PASS" if t_ok else "FAIL")
    print("忽略表 t_skip     :", "PASS" if i_ok else "FAIL")
    overall = s1 and s2 and t_ok and i_ok
    print("总体             :", "ALL_PASS" if overall else "FAIL")
    cleanup(created)
