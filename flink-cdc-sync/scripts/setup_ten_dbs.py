#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
创建 10 个跨主机同步测试库：
  - 源库  : 127.0.0.1:3306 (MySQL 8.0)  sync10_01..sync10_10，含 t_biz 表与测试数据
  - 目标库: 192.168.88.88:3306 (MySQL 5.7) sync10_01..sync10_10，仅建库 + 建表(空)
字符集统一使用 utf8mb4_general_ci，保证 5.7 目标可正确镜像建表（避免 0900_ai_ci 不兼容）。
运行: python scripts/setup_ten_dbs.py
"""
import pymysql

SOURCE = dict(host='127.0.0.1', port=3306, user='root', password='123456')
TARGET = dict(host='192.168.88.88', port=3306, user='root', password='123456')
N = 10

TABLE_DDL = """
CREATE TABLE IF NOT EXISTS `t_biz` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `biz_key`     VARCHAR(64) NOT NULL,
  `name`        VARCHAR(128) NOT NULL,
  `category`    VARCHAR(64) NOT NULL,
  `amount`      DECIMAL(18,2) NOT NULL DEFAULT 0.00,
  `quantity`    INT NOT NULL DEFAULT 0,
  `is_active`   TINYINT NOT NULL DEFAULT 1,
  `remark`      VARCHAR(255) NOT NULL DEFAULT '',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_biz_key` (`biz_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
"""


def connect(cfg, db=None):
    return pymysql.connect(host=cfg['host'], port=cfg['port'], user=cfg['user'],
                           password=cfg['password'], database=db, charset='utf8mb4',
                           connect_timeout=10, autocommit=False)


def setup_source(i):
    db = f"sync10_{i:02d}"
    c = connect(SOURCE)
    c.cursor().execute(
        f"CREATE DATABASE IF NOT EXISTS `{db}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
    c.commit(); c.close()
    c = connect(SOURCE, db); cur = c.cursor()
    cur.execute(TABLE_DDL)
    cur.execute("TRUNCATE TABLE t_biz")
    cnt = 60 + i * 20  # db01=80 ... db10=260
    rows = []
    for r in range(1, cnt + 1):
        biz = f"BK{i:02d}{r:05d}"
        name = f"name_{i:02d}_{r}"
        cat = ["A", "B", "C", "D"][r % 4]
        amount = round((r * 1.37 + i) * 10.5, 2)
        qty = (r * 3) % 1000
        active = r % 2
        remark = f"remark for {biz}"
        rows.append((biz, name, cat, amount, qty, active, remark))
    sql = ("INSERT INTO t_biz (biz_key,name,category,amount,quantity,is_active,remark) "
           "VALUES (%s,%s,%s,%s,%s,%s,%s)")
    cur.executemany(sql, rows)
    c.commit(); c.close()
    return db, cnt


def setup_target(i):
    db = f"sync10_{i:02d}"
    c = connect(TARGET)
    c.cursor().execute(
        f"CREATE DATABASE IF NOT EXISTS `{db}` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci")
    c.commit(); c.close()
    c = connect(TARGET, db); cur = c.cursor()
    cur.execute(TABLE_DDL)
    cur.execute("TRUNCATE TABLE t_biz")
    c.commit(); c.close()
    return db


if __name__ == '__main__':
    for i in range(1, N + 1):
        sdb, cnt = setup_source(i)
        tdb = setup_target(i)
        print(f"[{i:02d}] source {sdb} rows={cnt:4d} | target {tdb} created(empty)")
    print("SETUP DONE")
