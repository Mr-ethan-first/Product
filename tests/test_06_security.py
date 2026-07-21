# -*- coding: utf-8 -*-
"""安全与健壮性全场景测试：SQL 注入 / XSS / 异常输入 / 方法不允许 / Content-Type。

验证后端对恶意/异常输入不产生 5xx 裸堆栈、不泄漏内部信息、参数化查询有效。
"""
import pytest
import requests
from conftest import assert_ok, assert_err, unique_user, LOCAL_MYSQL

AUTH_REQUIRED = "2807003003"
AUTH_FAIL = "2807003001"
AUTH_PARAM = "2807003004"
PARAM_ERROR = "2807002001"


class TestSqlInjection:
    """SQL 注入防护：登录/注册字段必须参数化查询。"""

    @pytest.mark.parametrize("payload", [
        "admin' OR '1'='1",
        "admin'--",
        "admin'; DROP TABLE sys_user; --",
        "' OR 1=1 --",
        "admin\" OR \"1\"=\"1",
    ])
    def test_login_injection_rejected(self, anon, base_url, payload):
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": payload, "password": "anything"}, timeout=10)
        # 注入应被当作普通登录失败（200 AUTH_FAIL），绝不能 500
        assert r.status_code == 200, f"注入 payload 应 200: {r.status_code} {r.text[:200]}"
        assert r.json().get("success") is False

    def test_register_injection_safe(self, anon, base_url):
        # 用户名含注入串，作为普通字符串注册（应成功或参数错误，不能 500）
        u = "a'_" + unique_user("inj")
        if len(u) > 32:
            u = u[:32]
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": u, "password": "pass123"}, timeout=10)
        assert r.status_code in (200, 400), f"注入用户名不应 500: {r.status_code} {r.text[:200]}"

    def test_dbList_injection_filter(self, auth, base_url):
        # 过滤条件带注入串，应正常返回（参数化），不能 500
        r = auth.post(f"{base_url}/sync/db/list",
                      json={"page": 1, "pageSize": 5,
                            "condition": {"sourceDbName": "' OR 1=1 --"}},
                      timeout=15)
        assert r.status_code == 200, f"过滤注入不应 500: {r.status_code} {r.text[:200]}"


class TestXss:
    """XSS 防护：特殊字符应安全存储/回显，不触发执行。"""

    def test_register_xss_username_rejected_or_safe(self, anon, base_url):
        # 含 <script> 的用户名：长度若合法则注册，回显时不应未转义
        u = "<script>alert(1)</script>"
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": u, "password": "pass123"}, timeout=10)
        # 长度 33（>32）应参数错误；即便允许也应安全存储
        assert r.status_code in (200, 400, 409), f"XSS 用户名响应异常: {r.status_code}"


class TestMalformedInput:
    """畸形请求体处理。"""

    def test_malformed_json_login(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/login",
                      data="{not valid json",
                      headers={"Content-Type": "application/json"}, timeout=10)
        assert r.status_code == 400, f"畸形 JSON 应 400: {r.status_code}"

    def test_malformed_json_register(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register",
                      data="{broken",
                      headers={"Content-Type": "application/json"}, timeout=10)
        assert r.status_code == 400, f"畸形 JSON 应 400: {r.status_code}"

    def test_wrong_content_type(self, anon, base_url):
        # 用表单编码发登录，应被拒绝或 400（接口期望 JSON）
        r = anon.post(f"{base_url}/auth/login",
                      data="username=admin&password=admin123",
                      headers={"Content-Type": "application/x-www-form-urlencoded"}, timeout=10)
        # Spring 对 @RequestBody Map 在 form 编码下通常 400/415
        assert r.status_code in (400, 415), f"错误 Content-Type 响应: {r.status_code}"

    def test_very_long_password(self, anon, base_url):
        # 超长密码不应导致 500（应正常校验或失败）
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": "admin", "password": "x" * 10000}, timeout=10)
        assert r.status_code in (200, 400), f"超长密码响应: {r.status_code}"

    def test_extra_fields_ignored(self, anon, base_url):
        # 多余字段应被忽略，不影响登录
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": "admin", "password": "admin123",
                            "extra": "x", "is admin": True}, timeout=10)
        assert_ok(r)


class TestHttpMethod:
    """HTTP 方法约束。"""

    def test_get_login_not_allowed(self, anon, base_url):
        r = anon.get(f"{base_url}/auth/login", timeout=10)
        assert r.status_code in (405, 404), f"GET /auth/login 应 405/404: {r.status_code}"

    def test_get_register_not_allowed(self, anon, base_url):
        r = anon.get(f"{base_url}/auth/register", timeout=10)
        assert r.status_code in (405, 404), f"GET /auth/register 应 405/404: {r.status_code}"

    def test_post_status_not_allowed(self, auth, base_url):
        r = auth.post(f"{base_url}/sync/status", json={}, timeout=10)
        assert r.status_code in (405, 404), f"POST /sync/status 应 405/404: {r.status_code}"


class TestAuthProtectionMatrix:
    """鉴权拦截矩阵：所有 /sync/** 接口未登录必须 401 AUTH_REQUIRED。"""

    @pytest.mark.parametrize("method,path,body", [
        ("GET",  "/sync/status", None),
        ("GET",  "/sync/ipList", None),
        ("POST", "/sync/db/list", {"page": 1, "pageSize": 5, "condition": {}}),
        ("GET",  "/sync/mappings", None),
        ("GET",  "/sync/databases/127.0.0.1", None),
        ("GET",  "/sync/1", None),
        ("POST", "/sync/mapping/test", {"sourceHost": "127.0.0.1"}),
        ("POST", "/sync/sourceDatabases", {"sourceHost": "127.0.0.1"}),
        ("POST", "/sync/sourceTables", {"sourceHost": "127.0.0.1", "database": "x"}),
        ("POST", "/sync/mapping/add", {"sourceHost": "127.0.0.1"}),
        ("POST", "/sync/mapping/remove", {"instanceKey": "x->y"}),
        ("POST", "/sync/resyncDatabases", [{"ip": "127.0.0.1", "sourceDbName": "x"}]),
    ])
    def test_require_auth(self, anon, base_url, method, path, body):
        if method == "GET":
            r = anon.get(f"{base_url}{path}", timeout=10)
        else:
            r = anon.post(f"{base_url}{path}", json=body, timeout=10)
        assert_err(r, 401, AUTH_REQUIRED)
