from fastapi.testclient import TestClient

import sure_weather.api as api


class _FakeResponse:
    def raise_for_status(self):
        pass

    def json(self):
        return [{"lat": "51.5", "lon": "-0.12", "display_name": "London", "type": "city"}]


class _FakeClient:
    def __init__(self):
        self.params = []

    def get(self, url, params=None, **kwargs):
        self.params.append(params)
        return _FakeResponse()


def _client(monkeypatch):
    fake = _FakeClient()
    monkeypatch.setattr(api, "get_client", lambda: fake)
    api._GEOCODE_CACHE.clear()
    return TestClient(api.app), fake


def test_geocode_follows_ui_language(monkeypatch):
    c, fake = _client(monkeypatch)
    r = c.get("/geocode?q=Londres&lang=en")
    assert r.status_code == 200
    assert fake.params[-1]["accept-language"] == "en"
    # Same query in French is a separate cache entry, not the English names.
    c.get("/geocode?q=Londres&lang=fr")
    assert [p["accept-language"] for p in fake.params] == ["en", "fr"]


def test_geocode_defaults_to_french_and_rejects_other_languages(monkeypatch):
    c, fake = _client(monkeypatch)
    assert c.get("/geocode?q=Lyon").status_code == 200
    assert fake.params[-1]["accept-language"] == "fr"
    assert c.get("/geocode?q=Lyon&lang=de").status_code == 422
