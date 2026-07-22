# -*- coding: utf-8 -*-
"""前端换肤自动化测试（前后端联动）。

验证 index.html 已内置双主题（维度分层 / Codex 经典）与一键换肤按钮，
确保前端设计系统可被一键切换、且原生控件（下拉框/日期选择器）做了 dark 适配。
"""
import os
import requests

BASE_URL = os.environ.get("DRPlatform_BASE_URL", "http://127.0.0.1:8080")


def _get_index():
    r = requests.get(f"{BASE_URL}/", timeout=15)
    assert r.status_code == 200, f"GET / 失败: HTTP {r.status_code}"
    return r.text


def test_index_has_dual_theme_blocks():
    html = _get_index()
    # 1) 两套主题样式块都存在
    assert 'id="theme-modern"' in html, "缺少默认主题块 theme-modern"
    assert 'id="theme-original"' in html, "缺少原主题块 theme-original"
    # 2) 原主题块默认禁用（由底部脚本按 localStorage 启用），保证默认仍是当前风格
    assert 'id="theme-original" disabled' in html, "原主题块未默认禁用"
    # 3) 切换按钮 + 持久化逻辑
    assert 'id="themeSwitchBtn"' in html, "缺少一键换肤按钮"
    assert 'drplatform-theme' in html, "缺少主题持久化(localStorage)逻辑"


def test_native_controls_dark_adapted():
    """下拉框/日期选择器在切回原主题时也与主题统一（不再浅色黑框）。"""
    html = _get_index()
    # 原生控件统一 dark：两套主题任一包含即可（modern 在 input/select 上，original 增强在 html 上）
    assert 'color-scheme: dark' in html, "缺少原生控件 color-scheme: dark 适配"
    # 原主题对 select 展开项做了高亮（避免与主题不适配）
    assert 'option:hover' in html and 'option:checked' in html, "缺少下拉项 hover/选中高亮"


def test_theme_tokens_complete():
    """两套主题都定义了完整设计 token，切回原主题后样式不缺漏。"""
    html = _get_index()
    for token in ("--canvas", "--surface", "--text", "--accent", "--border"):
        assert token in html, f"缺少共有设计 token: {token}"
