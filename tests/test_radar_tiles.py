"""Observed-radar tile recolouring (web/radar-tiles.js), run through Node."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

WEB = Path(__file__).resolve().parent.parent / "src" / "sure_weather" / "web"
JS = (WEB / "radar-tiles.js").as_posix()

pytestmark = pytest.mark.skipif(shutil.which("node") is None, reason="node not installed")


def node(expr: str):
    script = f"const R = require({json.dumps(JS)}); console.log(JSON.stringify({expr}));"
    out = subprocess.run(["node", "-e", script], capture_output=True, text=True, check=True)
    return json.loads(out.stdout)


def test_tile_url():
    assert node('R.tileUrl("https://h", "/v2/radar/1", 7, 66, 45)') == \
        "https://h/v2/radar/1/512/7/66/45/2/0_1_0.png"


def test_dbz_to_mmh():
    assert 0.30 < node("R.dbzToMmh(15)") < 0.34
    assert 5.3 < node("R.dbzToMmh(35)") < 5.9


def test_ramp_color_bounds():
    assert node("R.rampColor(0.05)") is None
    assert node("R.rampColor(50)") == node("R.RAMP[R.RAMP.length - 1][1]")


def test_recolor():
    res = node(
        "(() => { const d = new Uint8ClampedArray(["
        "0x82,0x7b,0x69,0x49,"      # beige low echo
        "0x88,0xdd,0xee,0xff,"      # 15 dBZ rain
        "0xbf,0xff,0xff,0xff,"      # snow
        "0x12,0x34,0x56,0xff]);"    # unknown opaque
        "R.recolor(d); return {d: Array.from(d), r: R.rampColor(R.dbzToMmh(15))}; })()"
    )
    d = res["d"]
    assert d[3] == 0
    assert d[4:8] == res["r"] + [255]
    assert d[8:12] == [0xBF, 0xFF, 0xFF, 0xFF]
    assert d[15] == 0


def test_palette_tables():
    rain = node("R.RAIN")
    snow = node("R.SNOW")
    assert len(rain) == 50
    assert len(set(rain)) == 50
    assert not set(rain) & set(snow)


def test_wiring():
    html = (WEB / "index.html").read_text(encoding="utf-8")
    assert 0 <= html.index("/static/radar-tiles.js") < html.index("/static/app.js")
    app = (WEB / "app.js").read_text(encoding="utf-8")
    assert "/256/" not in app
    assert "1_1_0" not in app
