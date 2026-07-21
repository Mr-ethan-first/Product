#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
对源库(127.0.0.1)执行实时 增/改/删，用于验证同步引擎实时镜像与删除对账。
每个库: +3 插入 / +2 更新(amount+1000) / -1 删除
运行: python scripts/mutate_source.py   (随后等待 ~15s 再跑 check_consistency.py)
"""
import pymysql

SOURCE = dict(host='127.0.0.1', port=3306, user='root', password='123456')
N = 10
TABLE = 't_biz'


def connect(db):
    return pymysql.connect(host=SOURCE['host'], port=SOURCE['port'], user=SOURCE['user'],
                           password=SOURCE['password'], database=db, charset='utf8mb4',
                           connect_timeout=10)


def mutate(i):
    db = f"sync10_{i:02d}"
    c = connect(db); cur = c.cursor()
    cur.execute("SELECT MAX(id) FROM t_biz")
    maxid = cur.fetchone()[0] or 0
    # INSERT 3 new rows
    new = [(f"RT{i:02d}{r:04d}", f"rtname_{i}_{r}", "X", 99.99, 7, 1, "realtime insert")
           for r in range(1, 4)]
    cur.executemany(
        "INSERT INTO t_biz (biz_key,name,category,amount,quantity,is_active,remark) "
        "VALUES (%s,%s,%s,%s,%s,%s,%s)", new)
    # UPDATE 2 existing rows (id<=2) amount+1000, 触发 update_time 变化
    cur.execute("UPDATE t_biz SET amount=amount+1000, update_time=NOW() WHERE id<=2")
    # DELETE 1 smallest id -> 触发目标库删除对账
    cur.execute("DELETE FROM t_biz ORDER BY id LIMIT 1")
    c.commit(); c.close()
    print(f"[{db}] +3 insert, +2 update(amount+1000), -1 delete (maxid was {maxid})")


if __name__ == '__main__':
    for i in range(1, N + 1):
        mutate(i)
    print("MUTATE DONE")
