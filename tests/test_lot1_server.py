"""Lot 1 of the 2026-11 code audit: retention, rate limits, tile budget, push 410."""

from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi.testclient import TestClient

from sure_weather import api, tiles
from sure_weather.config import Config
from sure_weather.models import ForecastSample, Observation, Residual
from sure_weather.service import CellBudgetExceeded, WeatherService
from sure_weather.storage import Storage

NOW = datetime(2026, 11, 17, 12, tzinfo=timezone.utc)
CELL = "48.9,2.4"


def _fc(issued_h: float, valid_h: float, value: float = 10.0, provider="gfs_seamless"):
    return ForecastSample(
        provider=provider,
        cell_key=CELL,
        variable="temperature_2m",
        issued_at=NOW + timedelta(hours=issued_h),
        valid_at=NOW + timedelta(hours=valid_h),
        value=value,
    )


def _count(storage: Storage, table: str) -> int:
    return storage.locked_fetchone(f"SELECT COUNT(*) FROM {table}")[0]


# ---- retention ----


def test_purge_drops_old_and_superseded_rows(tmp_path):
    storage = Storage(tmp_path / "w.db")
    storage.insert_forecasts(
        [
            # 40 days old: past the 30-day window.
            _fc(-40 * 24 - 2, -40 * 24),
            # Past valid time -10 h, three issues in the 12 h bucket (7..24 h
            # ahead) and one in the 48 h bucket: only the latest 12 h issue and
            # the 48 h one survive.
            _fc(-30, -10),
            _fc(-28, -10),
            _fc(-25, -10, value=11.0),
            _fc(-50, -10),
            # Analysis row (issued == valid): kept.
            _fc(-10, -10),
            # Future rows: two runs, both kept (serving picks among them).
            _fc(-12, 30),
            _fc(-1, 30),
        ]
    )
    storage.insert_observations(
        [
            Observation("era5_seamless", CELL, "temperature_2m", NOW - timedelta(days=40), 5.0),
            Observation("era5_seamless", CELL, "temperature_2m", NOW - timedelta(days=2), 5.0),
        ]
    )
    storage.insert_residuals(
        [
            Residual("gfs_seamless", CELL, "temperature_2m", NOW - timedelta(days=40), 3.0, 6, 5),
            Residual("gfs_seamless", CELL, "temperature_2m", NOW - timedelta(days=2), 3.0, 6, 5),
        ]
    )
    assert _count(storage, "forecasts") == 8

    counts = storage.purge(keep_days=30, now=NOW)

    assert counts == {
        "forecasts_old": 1,
        "forecasts_superseded": 2,
        "observations": 1,
        "residuals": 1,
    }
    rows = storage.locked_execute(
        "SELECT issued_at, valid_at, value FROM forecasts ORDER BY valid_at, issued_at"
    )
    kept_past = [(r[0], r[2]) for r in rows if r[1] < NOW.isoformat()]
    assert kept_past == [
        ((NOW - timedelta(hours=50)).isoformat(), 10.0),
        ((NOW - timedelta(hours=25)).isoformat(), 11.0),
        ((NOW - timedelta(hours=10)).isoformat(), 10.0),
    ]
    assert len([r for r in rows if r[1] > NOW.isoformat()]) == 2
    assert _count(storage, "observations") == 1
    assert _count(storage, "residuals") == 1
    # Idempotent.
    assert sum(storage.purge(keep_days=30, now=NOW).values()) == 0
    storage.close()


def test_new_database_uses_incremental_vacuum(tmp_path):
    storage = Storage(tmp_path / "w.db")
    assert storage.locked_fetchone("PRAGMA auto_vacuum")[0] == 2
    storage.close()


def test_maybe_purge_is_throttled(tmp_path):
    storage = Storage(tmp_path / "w.db")
    service = WeatherService(storage, Config(db_path=tmp_path / "w.db"))
    assert service.maybe_purge() is not None
    assert service.maybe_purge() is None
    assert service.maybe_purge(force=True) is not None
    storage.close()


def test_new_cell_budget(tmp_path, monkeypatch):
    from sure_weather import service as service_mod

    monkeypatch.setattr(service_mod, "_NEW_CELLS_PER_HOUR", 2)
    storage = Storage(tmp_path / "w.db")
    service = WeatherService(storage, Config(db_path=tmp_path / "w.db"))
    service._claim_new_cell()
    service._claim_new_cell()
    with pytest.raises(CellBudgetExceeded):
        service._claim_new_cell()
    storage.close()


# ---- rate limits ----


@pytest.fixture
def client(monkeypatch):
    api._RATE_BUCKETS.clear()
    monkeypatch.setattr(api._service, "forecast", lambda lat, lon, hours=48: {"ok": 1})
    yield TestClient(api.app)
    api._RATE_BUCKETS.clear()


def test_weather_is_rate_limited(client):
    for _ in range(api._FORECAST_LIMIT):
        assert client.get("/weather?lat=48.85&lon=2.35").status_code == 200
    r = client.get("/weather?lat=48.85&lon=2.35")
    assert r.status_code == 429
    # /now and /widget.svg share the forecast bucket.
    assert client.get("/now?lat=48.85&lon=2.35").status_code == 429
    assert client.get("/widget.svg?lat=48.85&lon=2.35").status_code == 429


def test_cell_budget_maps_to_429(client, monkeypatch):
    def boom(lat, lon, hours=48):
        raise CellBudgetExceeded()

    monkeypatch.setattr(api._service, "forecast", boom)
    r = client.get("/weather?lat=10&lon=10")
    assert r.status_code == 429
    assert r.headers["retry-after"] == "600"


def test_push_subscribe_is_rate_limited(client, monkeypatch, tmp_path):
    monkeypatch.setattr(api, "_storage", Storage(tmp_path / "w.db"))
    body = {"endpoint": "https://push.example.com/x", "keys": {"p256dh": "a", "auth": "b"}}
    for _ in range(10):
        assert client.post("/push/subscribe", json=body).status_code == 200
    assert client.post("/push/subscribe", json=body).status_code == 429


def test_rate_buckets_are_pruned(monkeypatch):
    api._RATE_BUCKETS.clear()
    monkeypatch.setattr(api, "_RATE_BUCKETS_MAX", 3)
    for i in range(4):
        api._RATE_BUCKETS[f"old:{i}"] = [0.0]
    api._rate_limit("fresh", limit=5)
    assert list(api._RATE_BUCKETS) == ["fresh"]
    api._RATE_BUCKETS.clear()


# ---- push: dead subscriptions ----


class _GoneResponse:
    """Like requests.Response for a 410: falsy, since bool() returns .ok."""

    status_code = 410

    def __bool__(self):
        return False


def test_push_test_removes_gone_subscriptions(client, monkeypatch, tmp_path):
    import pywebpush

    storage = Storage(tmp_path / "w.db")
    storage.add_push_subscription("https://push.example.com/dead", "k", "a")
    storage.add_push_subscription("https://push.example.com/live", "k", "a")
    monkeypatch.setattr(api, "_storage", storage)
    monkeypatch.setattr(api, "_get_vapid_keys", lambda: {"private": "x", "public": "y"})
    monkeypatch.setenv("SURE_WEATHER_ADMIN_TOKEN", "t0ken")

    def fake_webpush(subscription_info, **kwargs):
        if subscription_info["endpoint"].endswith("dead"):
            raise pywebpush.WebPushException("gone", response=_GoneResponse())

    monkeypatch.setattr(pywebpush, "webpush", fake_webpush)
    r = client.post("/push/test?token=t0ken")
    assert r.status_code == 200
    assert r.json() == {"sent": 1}
    assert [s["endpoint"] for s in storage.list_push_subscriptions()] == [
        "https://push.example.com/live"
    ]


# ---- tile budget ----


class _FakeClient:
    def __init__(self, response=None, exc=None):
        self.calls = 0
        self.response = response
        self.exc = exc

    def get(self, url, params=None, timeout=None):
        self.calls += 1
        if self.exc is not None:
            raise self.exc
        return self.response


@pytest.fixture
def fresh_tiles(monkeypatch):
    tiles._tile_cache.clear()
    tiles._spent.clear()
    tiles._failed.clear()
    monkeypatch.setattr(tiles, "_cooldown_until", 0.0)
    yield
    tiles._tile_cache.clear()
    tiles._spent.clear()
    tiles._failed.clear()


def _ok_response(points: int) -> httpx.Response:
    req = httpx.Request("GET", "https://api.open-meteo.com/v1/forecast")
    body = [{"current": {"temperature_2m": 12.0}} for _ in range(points)]
    return httpx.Response(200, json=body, request=req)


def test_tile_budget_is_global(fresh_tiles, monkeypatch):
    fake = _FakeClient(response=_ok_response(64))
    monkeypatch.setattr("sure_weather.net.get_client", lambda: fake)
    # Room for two z<12 tiles (64 points each) per minute.
    monkeypatch.setattr(tiles, "_BUDGET_WINDOWS", ((60.0, 128), (86400.0, 10_000)))
    assert tiles.render_tile("temp", 9, 259, 176) is not None
    assert tiles.render_tile("temp", 9, 260, 176) is not None
    with pytest.raises(tiles.UpstreamBudgetExceeded):
        tiles.render_tile("temp", 9, 261, 176)
    assert fake.calls == 2
    # Cached tiles stay free.
    assert tiles.render_tile("temp", 9, 259, 176) is not None
    assert fake.calls == 2


def test_tile_failures_are_cached(fresh_tiles, monkeypatch):
    fake = _FakeClient(exc=httpx.ConnectError("down"))
    monkeypatch.setattr("sure_weather.net.get_client", lambda: fake)
    assert tiles.render_tile("temp", 9, 259, 176) is None
    assert tiles.render_tile("temp", 9, 259, 176) is None
    assert fake.calls == 1


def test_upstream_429_pauses_all_tiles(fresh_tiles, monkeypatch):
    req = httpx.Request("GET", "https://api.open-meteo.com/v1/forecast")
    fake = _FakeClient(response=httpx.Response(429, request=req))
    monkeypatch.setattr("sure_weather.net.get_client", lambda: fake)
    assert tiles.render_tile("temp", 9, 259, 176) is None
    assert tiles.render_tile("precip", 9, 100, 100) is None
    assert tiles.wind_grid(49, 2, 48, 3) is None
    assert fake.calls == 1


def test_tile_route_returns_503_when_budget_spent(monkeypatch):
    def spent(*a, **k):
        raise tiles.UpstreamBudgetExceeded()

    api._RATE_BUCKETS.clear()
    monkeypatch.setattr(api, "render_tile", spent)
    monkeypatch.setattr(api, "render_wind_grid", spent)
    c = TestClient(api.app)
    r = c.get("/tile/temp/9/259/176.png")
    assert r.status_code == 503
    assert r.headers["retry-after"] == "60"
    assert c.get("/wind-grid?lat_n=49&lon_w=2&lat_s=48&lon_e=3").status_code == 503
    api._RATE_BUCKETS.clear()
