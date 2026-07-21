#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""将默认 profile 的 geodrsync.mappings 替换为 10 条跨主机映射(源127.0.0.1 -> 目标192.168.88.88)。"""

p = "src/main/resources/application.yml"
t = open(p, encoding="utf-8").read()

L = ["  mappings:"]
for i in range(1, 11):
    d = "sync10_%02d" % i
    L += [
        "    - source-host: 127.0.0.1",
        "      source-port: 3306",
        "      source-db-name: " + d,
        "      source-user: root",
        "      source-password: 123456",
        "      target-host: 192.168.88.88",
        "      target-port: 3306",
        "      target-db-name: " + d,
        "      target-user: root",
        "      target-password: 123456",
    ]
new_block = "\n".join(L)

# 仅替换默认 profile 的第一段 mappings（linux profile 保持不变）
start = t.index("  mappings:")
end = t.index("\nmanagement:")
open(p, "w", encoding="utf-8").write(t[:start] + new_block + t[end:])
print("patched default mappings -> 1 mappings key with 10 cross-host items")
