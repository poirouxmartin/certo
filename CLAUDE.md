# CLAUDE.md

Python 3.12+ service: collects weather forecasts and observations, learns per-provider bias in ~0.1 degree cells in SQLite, and serves a fused forecast through FastAPI. Last commit 2026-09-05. Concept and usage: README.md. Dev rules: AGENTS.md.

## Commands (verified in pyproject.toml, cli.py, api.py)

- Install: `uv venv` then `uv pip install -e ".[dev]"`.
- CLI (entry point `sure-weather` = `sure_weather.cli:main`): `collect`, `observe`, `calibrate`, `forecast`, `stations`, `verify`, `backfill`. Run `sure-weather <cmd> --help` for flags.
- API: `uvicorn sure_weather.api:app`. Routes: `/weather`, `/geocode`, `/radar`, `/tile/{layer}/{z}/{x}/{y}.png`, `/wind-grid`, `/now`, `/widget.svg`, `/health`, `/push/*`, plus UI at `/` and `/mini`.
- Tests: `.venv/Scripts/python -m pytest` (Windows path from README; on other OS use `.venv/bin/python`). `pythonpath = ["src"]` is set in pyproject.

## Layout (src/sure_weather/)

- `cli.py`: subcommands. `api.py`: FastAPI app (`app`), push, rate limiting, static UI.
- `collectors/open_meteo.py`: NWP models and high-resolution models (keyless). `collectors/metar.py`: airport stations (NOAA METAR), used as ground truth.
- `calibration.py`: residuals and provider bias/rmse. `verify.py`: reliability tables. Ground-truth priority: station, then reanalysis (ERA5).
- `fusion/`: weighted consensus per (variable, cell, horizon). Tolerances per variable define calibrated confidence.
- `service.py`: forecast assembly, memoized for a few minutes; horizon extrapolation for buckets without data.
- `storage.py`: SQLite schema. `grid.py`: ~0.1 degree cells. `models.py`: variables and data types. `net.py`: shared httpx client. `tiles.py`: PNG tiles and wind grids (numpy only).
- `config.py`: settings. `web/`: static UI (`index.html`, `mini.html`, `app.js`, `sw.js`, `manifest.webmanifest`).
- `Caddyfile` at repo root: reverse proxy example (not documented elsewhere).
- Tests: `tests/test_*.py`, one per area (calibration, fusion, grid, metar, tiles, tile API, reliability, wind direction).

## Conventions

- Code comments explain why (measured values, data-source quirks). Keep that style when changing a constant.
- Upstream HTTP goes through `net.py` (pooled client). Do not create per-call clients.
- Storage schema changes need a migration path: see the comment at `storage.py:77-81` on the earlier horizon-less schema.

## Pitfalls

- Admin-only push endpoints need `SURE_WEATHER_ADMIN_TOKEN` (read in `api.py`). Never write its value into files or docs.
- `.venv/` is in the working tree and gitignored. Do not commit it.
- Some forecasts use keyless APIs with rate limits; `api.py` has per-IP limiting. Keep limits when adding endpoints.
- README status: the web UI is present (`web/`, served by `api.py`).
