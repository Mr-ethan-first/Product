# -*- coding: utf-8 -*-
"""隔离实验：源库 docker stop 时，同步服务 JVM 是否真死？

精确跟踪 pytest 托管的 app 子进程 PID，观察 docker stop 源库前后：
  - JVM 进程是否存活（proc.poll()/pid 存在）
  - /sync/status 是否返回 200
  - app-lab-iso.log 是否出现崩溃栈
"""
import os, time, subprocess, sys, requests, json

JAVA = r"D:\software\jdk-21.0.11\bin\java.exe"
APP_JAR = r"D:\WorkSpace\flink-cdc-sync\remote-data-sync\target\remote-data-sync.jar"
APP_CWD = r"D:\WorkSpace\flink-cdc-sync\remote-data-sync"
LOG = r"D:\WorkSpace\flink-cdc-sync\tests\docker-lab\app-lab-iso.log"
URL = "http://127.0.0.1:8899"
SRC_CTR = "drp-src-mysql"
TGT_CTR = "drp-tgt-mysql"


def docker(args, timeout=150):
    r = subprocess.run(["docker"] + args, capture_output=True, text=True, timeout=timeout)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def login():
    s = requests.Session()
    s.headers.update({"Content-Type": "application/json"})
    r = s.post(f"{URL}/auth/login", json={"username": "admin", "password": "admin123"}, timeout=20)
    assert r.status_code == 200 and r.json().get("success") is True, f"login {r.status_code} {r.text[:120]}"
    return s


def app_alive():
    try:
        r = requests.get(f"{URL}/sync/status", timeout=10)
        return r.status_code, r.text[:200]
    except Exception as e:
        return None, f"EXC {e}"


def java_alive(pid):
    try:
        p = subprocess.run(["powershell", "-NoProfile", "-Command",
                            f"Get-Process -Id {pid} -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id"],
                           capture_output=True, text=True, timeout=20)
        return p.stdout.strip().isdigit()
    except Exception:
        return False


def main():
    # 确保容器 up
    for c in (SRC_CTR, TGT_CTR):
        rc, out, err = docker(["start", c])
        print(f"[docker start {c}] rc={rc} {out}")
    time.sleep(5)

    # 若 8899 已被占用，先记录占用者
    rc, out, err = docker(["ps"])  # noop to import
    occ = subprocess.run(["powershell", "-NoProfile", "-Command",
                          "Get-NetTCPConnection -LocalPort 8899 -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess"],
                         capture_output=True, text=True, timeout=20).stdout.strip()
    if occ:
        print(f"[WARN] 8899 already occupied by PID {occ}; killing it")
        subprocess.run(["powershell", "-NoProfile", "-Command", f"Stop-Process -Id {occ} -Force"],
                       capture_output=True, timeout=20)
        time.sleep(3)

    env = dict(os.environ, SPRING_PROFILES_ACTIVE="dockerlab")
    env.pop("SERVER__PORT", None)
    env.pop("SERVER_PORT", None)
    with open(LOG, "w", encoding="utf-8") as log:
        proc = subprocess.Popen([JAVA, "-jar", APP_JAR, "--server.port=8899"],
                                cwd=APP_CWD, env=env, stdout=log, stderr=subprocess.STDOUT)

    print(f"[start] app PID={proc.pid}")
    # 等待就绪
    ready = False
    for _ in range(40):
        time.sleep(3)
        if proc.poll() is not None:
            print(f"[FATAL] app exited early with code {proc.poll()}")
            break
        try:
            login()
            ready = True
            break
        except Exception:
            pass
    print(f"[ready] {ready}; poll={proc.poll()}; java_alive={java_alive(proc.pid)}")

    # 源库断电
    rc, out, err = docker(["stop", SRC_CTR])
    print(f"[docker stop {SRC_CTR}] rc={rc} {out} {err[:120]}")
    for i in range(10):  # 20s
        time.sleep(2)
        sc, body = app_alive()
        alive = java_alive(proc.pid)
        polled = proc.poll()
        print(f"  t+{(i+1)*2}s status_code={sc} java_alive={alive} proc_poll={polled}")

    # 恢复
    rc, out, err = docker(["start", SRC_CTR])
    print(f"[docker start {SRC_CTR}] rc={rc} {out}")

    # 收尾
    try:
        subprocess.run(["powershell", "-NoProfile", "-Command", f"Stop-Process -Id {proc.pid} -Force"],
                       capture_output=True, timeout=20)
    except Exception:
        pass
    print("[done] stopped app")


if __name__ == "__main__":
    main()
