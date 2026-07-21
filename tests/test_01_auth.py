# -*- coding: utf-8 -*-
"""鉴权模块全场景测试：注册 / 登录 / 登出 / 当前用户 / 异常。"""
import pytest
from conftest import assert_ok, assert_err, unique_user

# 错误码
AUTH_PARAM = "2807003004"
AUTH_FAIL = "2807003001"
AUTH_DUPLICATE = "2807003002"
PARAM_ERROR = "2807002001"


class TestRegister:
    """注册接口 /auth/register。"""

    def test_register_valid(self, anon, base_url):
        u = unique_user("reg")
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": u, "password": "pass123"}, timeout=10)
        assert_ok(r)

    def test_register_then_login(self, anon, base_url):
        u = unique_user("rl")
        assert_ok(anon.post(f"{base_url}/auth/register",
                            json={"username": u, "password": "pass123"}, timeout=10))
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": u, "password": "pass123"}, timeout=10)
        assert_ok(r)
        assert r.json()["data"]["username"] == u

    def test_register_duplicate(self, anon, base_url):
        u = unique_user("dup")
        assert_ok(anon.post(f"{base_url}/auth/register",
                            json={"username": u, "password": "pass123"}, timeout=10))
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": u, "password": "pass123"}, timeout=10)
        assert_err(r, 409, AUTH_DUPLICATE)

    def test_register_short_username(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": "ab", "password": "pass123"}, timeout=10)
        assert_err(r, 400, AUTH_PARAM)

    def test_register_long_username(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": "x" * 33, "password": "pass123"}, timeout=10)
        assert_err(r, 400, AUTH_PARAM)

    def test_register_short_password(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": unique_user("sp"), "password": "12345"}, timeout=10)
        assert_err(r, 400, AUTH_PARAM)

    def test_register_empty_username(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": "", "password": "pass123"}, timeout=10)
        assert_err(r, 400, AUTH_PARAM)

    def test_register_empty_password(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register",
                      json={"username": unique_user("ep"), "password": ""}, timeout=10)
        assert_err(r, 400, AUTH_PARAM)

    def test_register_null_body(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register", json=None, timeout=10)
        # null body 可能触发 PARAM_ERROR（body缺失）或 AUTH_PARAM（body为null被方法处理）
        j = assert_err(r, 400)
        assert str(j.get("code")) in (PARAM_ERROR, AUTH_PARAM), f"错误码异常: {j}"

    def test_register_missing_fields(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/register", json={"username": "only"}, timeout=10)
        assert_err(r, 400, AUTH_PARAM)


class TestLogin:
    """登录接口 /auth/login。"""

    def test_login_admin_success(self, auth, base_url):
        # auth fixture 已登录，这里复用验证
        r = auth.get(f"{base_url}/auth/me", timeout=10)
        j = assert_ok(r)
        assert j["data"]["loggedIn"] is True

    def test_login_wrong_password(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": "admin", "password": "wrongpass999"}, timeout=10)
        assert_err(r, 200, AUTH_FAIL)

    def test_login_nonexistent_user(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": "nouser_xyz_001", "password": "pass123"}, timeout=10)
        assert_err(r, 200, AUTH_FAIL)

    def test_login_empty_credentials(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": "", "password": ""}, timeout=10)
        assert_err(r, 400, AUTH_PARAM)

    def test_login_null_body(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/login", json=None, timeout=10)
        j = assert_err(r, 400)
        assert str(j.get("code")) in (PARAM_ERROR, AUTH_PARAM), f"错误码异常: {j}"

    def test_login_sets_session_cookie(self, anon, base_url):
        r = anon.post(f"{base_url}/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=10)
        assert_ok(r)
        # 会话 Cookie 必须存在（JSESSIONID）
        cookies = {c.name for c in r.cookies}
        assert "JSESSIONID" in cookies, f"未下发会话 Cookie: {cookies}"


class TestMeAndLogout:
    """/auth/me 与 /auth/logout。"""

    def test_me_before_login(self, anon, base_url):
        r = anon.get(f"{base_url}/auth/me", timeout=10)
        j = assert_ok(r)
        assert j["data"]["loggedIn"] is False

    def test_me_after_login(self, auth, base_url):
        r = auth.get(f"{base_url}/auth/me", timeout=10)
        j = assert_ok(r)
        assert j["data"]["loggedIn"] is True
        assert j["data"]["username"] == "admin"

    def test_logout(self, anon, base_url):
        # 先登录
        anon.post(f"{base_url}/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=10)
        r = anon.post(f"{base_url}/auth/logout", timeout=10)
        assert_ok(r)

    def test_me_after_logout(self, anon, base_url):
        anon.post(f"{base_url}/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=10)
        anon.post(f"{base_url}/auth/logout", timeout=10)
        r = anon.get(f"{base_url}/auth/me", timeout=10)
        j = assert_ok(r)
        assert j["data"]["loggedIn"] is False

    def test_logout_without_login(self, anon, base_url):
        # 未登录登出也应 200（幂等）
        r = anon.post(f"{base_url}/auth/logout", timeout=10)
        assert_ok(r)

    def test_concurrent_sessions_same_user(self, anon, base_url):
        # 同一账号两个会话，互不影响
        s1 = anon
        s2 = pytest.importorskip("requests").Session()
        s2.headers.update({"Content-Type": "application/json"})
        assert_ok(s1.post(f"{base_url}/auth/login",
                          json={"username": "admin", "password": "admin123"}, timeout=10))
        assert_ok(s2.post(f"{base_url}/auth/login",
                          json={"username": "admin", "password": "admin123"}, timeout=10))
        j1 = assert_ok(s1.get(f"{base_url}/auth/me", timeout=10))
        j2 = assert_ok(s2.get(f"{base_url}/auth/me", timeout=10))
        assert j1["data"]["loggedIn"] and j2["data"]["loggedIn"]
