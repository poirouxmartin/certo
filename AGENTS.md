# AGENTS.md — Certo

## Description

Multi-provider weather app with a per-provider confidence system for a precise final estimate. Cross-references multiple sources, including local weather stations.

## Stack

- Python 3.12+
- FastAPI + Uvicorn
- httpx
- Pydantic
- NumPy

## Conventions

- `pip install -e ".[dev]"` — install dev
- `pytest` — tests (dossier `tests/`)
- Build : hatchling
- Source : `src/sure_weather/`
