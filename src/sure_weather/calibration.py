from __future__ import annotations

import math
from collections import defaultdict
from datetime import datetime, timedelta, timezone

import numpy as np

from .config import Config
from .fusion import ProviderStat, horizon_bucket, spatial_stats
from .models import ForecastSample, Observation, Residual, Variable
from .storage import Storage, _parse_dt
from .collectors.open_meteo import DEFAULT_ARCHIVE

PRIMARY_ARCHIVE = DEFAULT_ARCHIVE

# Providers considered "ground truth" in priority order for residual matching.
# Real station observations beat reanalysis: they measure the actual atmosphere
# instead of reconstructing it, so residuals computed against them are more
# honest and the learned bias/rmse transfers better to live forecasts.
_GROUND_TRUTH_RANK = {"station": 0, "model": 1}


def compute_residuals(
    forecasts: list[ForecastSample],
    observations: list[Observation],
    kinds: dict[str, str] | None = None,
) -> list[Residual]:
    """Match each forecast to the observation at its valid time, per cell+variable.

    A forecast is matched to the observation of the same rounded hour, else of
    an adjacent hour (so within +/- 1 h), in the same cell and variable. One
    residual per (provider, cell, variable, valid_at, horizon bucket). When
    several sources are present at the same timestamp
    the best ground truth wins: station observations outrank reanalysis.
    """
    kinds = kinds or {}
    # Index observations by (cell, variable, rounded time), preferring the
    # highest-ranked ground-truth provider (station > reanalysis).
    obs_index: dict[tuple[str, str, datetime], Observation] = {}
    for o in observations:
        rounded = o.time.replace(minute=0, second=0, microsecond=0)
        key = (o.cell_key, o.variable, rounded)
        rank = _GROUND_TRUTH_RANK.get(kinds.get(o.provider, "model"), 1)
        current_rank = (
            _GROUND_TRUTH_RANK.get(kinds.get(obs_index[key].provider, "model"), 1)
            if key in obs_index
            else 1
        )
        if key not in obs_index or rank <= current_rank:
            obs_index[key] = o

    residuals: list[Residual] = []
    for f in forecasts:
        rounded = f.valid_at.replace(minute=0, second=0, microsecond=0)
        obs = obs_index.get((f.cell_key, f.variable, rounded))
        if obs is None:
            # Try adjacent hour.
            for delta in (timedelta(hours=1), timedelta(hours=-1)):
                obs = obs_index.get((f.cell_key, f.variable, rounded + delta))
                if obs is not None:
                    break
        if obs is None:
            continue
        horizon = (f.valid_at - f.issued_at).total_seconds() / 3600.0
        if horizon < 0:
            continue
        # Wind direction is circular: a linear predicted-observed error
        # (350° vs 10° → 340°) is meaningless. The signed shortest angle
        # (-20° here) is stored as `predicted` against observed = 0, so the
        # SQL AVG(predicted - observed) is the mean angular bias and the rmse
        # the real angular error. Storing the observation twice (the old way)
        # learned rmse 0: weight frozen, confidence inflated, "calibrated"
        # shown for a variable that learned nothing.
        if f.variable == "wind_direction_10m":
            predicted = ((f.value - obs.value + 180.0) % 360.0) - 180.0
            observed = 0.0
        else:
            predicted, observed = f.value, obs.value
        residuals.append(
            Residual(
                provider=f.provider,
                cell_key=f.cell_key,
                variable=f.variable,
                valid_at=f.valid_at,
                horizon_h=horizon_bucket(f.valid_at, f.issued_at),
                predicted=predicted,
                observed=observed,
            )
        )
    return residuals


def learn_stats(residuals: list[Residual]) -> list[ProviderStat]:
    """Aggregate residuals into per (provider, cell, variable, horizon) stats."""
    groups: dict[tuple[str, str, str, float], list[float]] = defaultdict(list)
    for r in residuals:
        groups[(r.provider, r.cell_key, r.variable, r.horizon_h)].append(r.bias)

    now = datetime.now(timezone.utc)
    stats: list[ProviderStat] = []
    for key, biases in groups.items():
        arr = np.array(biases, dtype=float)
        samples = len(arr)
        stats.append(
            ProviderStat(
                provider=key[0],
                cell_key=key[1],
                variable=key[2],
                horizon_h=key[3],
                samples=samples,
                bias=float(np.mean(arr)),
                rmse=float(np.sqrt(np.mean(arr**2))),
                updated_at=now,
            )
        )
    return stats


def calibration_due(storage: Storage, config: Config) -> bool:
    """True if no calibration has been done in the last interval."""
    row = storage.locked_fetchone("SELECT MAX(valid_at) FROM residuals")
    if row is None or row[0] is None:
        return True
    latest = _parse_dt(row[0])
    return (datetime.now(timezone.utc) - latest) > timedelta(
        hours=config.calibration_interval_h
    )


# Horizon prior extrapolation: the archive that grounds long-horizon
# residuals lags ~5 days, so 12h/48h/120h buckets fill over days. Until a
# bucket has real residuals, extrapolate the nearest learned bucket with a
# documented error-growth factor — conservative by construction (rmse grows,
# sample weight shrinks), it yields cautious calibrated confidence instead
# of an uncalibrated guess for the first days of a cell's life.
_HORIZON_INFLATION = {3.0: 1.0, 12.0: 1.35, 48.0: 1.8, 120.0: 2.3}


def _extrapolate_horizon_priors(
    stats: dict[tuple[str, str, str, float], ProviderStat],
) -> dict[tuple[str, str, str, float], ProviderStat]:
    by_series: dict[tuple[str, str, str], list[ProviderStat]] = {}
    for key, s in stats.items():
        if s.samples >= 3:
            by_series.setdefault((key[0], key[1], key[2]), []).append(s)
    out = dict(stats)
    for (provider, cell_key, variable), learned in by_series.items():
        learned.sort(key=lambda s: s.horizon_h)
        base = learned[0]
        for bucket, factor in _HORIZON_INFLATION.items():
            key = (provider, cell_key, variable, bucket)
            if key in out:
                continue
            # Synthetic prior: deliberately < 3 samples so it NEVER counts
            # as calibrated (fusion requires samples >= 3). It only softens
            # the uncalibrated prior instead of faking learned confidence.
            out[key] = ProviderStat(
                provider=provider,
                cell_key=cell_key,
                variable=variable,
                horizon_h=bucket,
                samples=min(2, max(1, base.samples // 4)),
                bias=base.bias,
                rmse=base.rmse * factor,
                updated_at=base.updated_at,
            )
    return out


def build_stats(
    storage: Storage,
    since: datetime,
    until: datetime | None = None,
    now: datetime | None = None,
) -> dict[tuple[str, str, str, float], ProviderStat]:
    """Calibration stats exactly as production serves them.

    SQL aggregation of the residual window, spatial interpolation across
    cells, then horizon priors. Shared by the service and `verify`, so the
    reliability table scores the confidence that is actually served.
    """
    now = now or datetime.now(timezone.utc)
    stats: dict[tuple[str, str, str, float], ProviderStat] = {}
    for (
        provider,
        cell_key,
        variable,
        horizon_h,
        samples,
        bias,
        mse,
    ) in storage.residual_bias_stats(since, until):
        stats[(provider, cell_key, variable, horizon_h)] = ProviderStat(
            provider=provider,
            cell_key=cell_key,
            variable=variable,
            horizon_h=horizon_h,
            samples=samples,
            bias=float(bias),
            rmse=math.sqrt(mse) if mse is not None else 0.0,
            updated_at=now,
        )
    cells = storage.get_cells()
    if cells:
        stats = spatial_stats(stats, cells)
    return _extrapolate_horizon_priors(stats)
