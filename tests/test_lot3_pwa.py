"""Lot 3 of the 2026-11 code audit: the PWA must install under its own CSP."""

import re
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from sure_weather.api import app

WEB = Path(__file__).resolve().parents[1] / "src" / "sure_weather" / "web"


def _app_shell() -> list[str]:
    sw = (WEB / "sw.js").read_text(encoding="utf-8")
    block = re.search(r"const APP_SHELL = \[(.*?)\];", sw, re.S).group(1)
    return re.findall(r'"([^"]+)"', block)


@pytest.mark.parametrize("url", _app_shell())
def test_every_precached_url_exists(url):
    """One 404 makes cache.addAll reject: the worker never installs."""
    assert TestClient(app).get(url).status_code == 200, url


@pytest.mark.parametrize("page", ["index.html", "mini.html"])
def test_no_inline_script(page):
    """The server CSP is script-src 'self': inline scripts never run."""
    html = (WEB / page).read_text(encoding="utf-8")
    for tag in re.findall(r"<script\b[^>]*>", html):
        assert "src=" in tag, f"inline script in {page}: {tag}"


def test_csp_is_script_self_only():
    r = TestClient(app).get("/mini")
    csp = r.headers["content-security-policy"]
    assert "script-src 'self';" in csp
    assert "unsafe-inline" not in csp.split("script-src", 1)[1].split(";", 1)[0]


def test_sw_ignores_cross_origin_and_refreshes_tiles():
    sw = (WEB / "sw.js").read_text(encoding="utf-8")
    assert "url.origin !== self.location.origin" in sw
    assert "cacheFirst" not in sw
