#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
跨主机数据一致性校验：逐库比对 源(127.0.0.1) 与 目标(192.168.88.88) 的 t_biz 表。
比较维度：行数、按主键排序的内容 MD5、amount 汇总。
为降低对目标 MySQL 连接数的占用，脚本只保持 1 个源连接 + 1 个目标连接，
通过 USE db 在 10 个库之间切换，避免“每库一连接”撑爆 max_user_connections。
运行: python scripts/check_consistency.py [--mode historical|realtime]
退出码: 全部一致=0，存在不一致=1
"""
import sys, hashlib, argparse
from decimal import Decimal
from datetime import datetime
import pymysql

SOURCE = dict(host='127.0.0.1', port=3306, user='root', password='123456')
TARGET = dict(host='192.168.88.88', port=3306, user='root', password='123456')
N = 10
TABLE = 't_biz'


def connect(cfg):
    return pymysql.connect(host=cfg['host'], port=cfg['port'], user=cfg['user'],
                           password=cfg['password'], charset='utf8mb4',
                           connect_timeout=10)


def fetch(conn, db):
    cur = conn.cursor(pymysql.cursors.DictCursor)
    cur.execute(f"USE `{db}`")
    cur.execute(f"SELECT * FROM `{TABLE}` ORDER BY id")
    rows = cur.fetchall()
    cur.close()
    return rows


def canon(rows):
    h = hashlib.md5()
    for r in rows:
        items = []
        for k in sorted(r.keys()):
            v = r[k]
            if v is None:
                s = 'NULL'
            elif isinstance(v, (bytes, bytearray)):
                s = v.hex()
            elif isinstance(v, datetime):
                s = v.strftime('%Y-%m-%d %H:%M:%S')
            elif isinstance(v, Decimal):
                s = str(v)
            elif isinstance(v, float):
                s = repr(v)
            else:
                s = str(v)
            items.append(f"{k}={s}")
        h.update(("|".join(items) + "\n").encode('utf-8'))
    return h.hexdigest()


def amt_sum(rows):
    return sum((Decimal(str(r['amount'])) for r in rows), Decimal('0'))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--mode', default='historical', choices=['historical', 'realtime'])
    args = ap.parse_args()

    sc = connect(SOURCE)
    tc = connect(TARGET)
    all_ok = True
    print(f"mode={args.mode}")
    print(f"{'DB':<12} {'src':>6} {'tgt':>6} {'rows':>6} {'md5':>5} {'sum':>6} result")
    print("-" * 58)
    for i in range(1, N + 1):
        db = f"sync10_{i:02d}"
        s = fetch(sc, db); t = fetch(tc, db)
        scn, tcn = len(s), len(t)
        sm, tm = canon(s), canon(t)
        ss, ts = amt_sum(s), amt_sum(t)
        ok = (scn == tcn and sm == tm and ss == ts)
        all_ok = all_ok and ok
        print(f"{db:<12} {scn:>6} {tcn:>6} {str(scn == tcn):>6} "
              f"{('OK' if sm == tm else 'DIFF'):>5} {str(ss == ts):>6} {'PASS' if ok else 'FAIL'}")
    sc.close(); tc.close()
    print("-" * 58)
    print("CONSISTENCY:", "ALL_PASS" if all_ok else "HAS_FAIL")
    sys.exit(0 if all_ok else 1)


if __name__ == '__main__':
    main()
