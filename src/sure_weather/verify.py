from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta, timezone

from .calibration import build_stats
from .config import Config
from .fusion import _VARIABLE_TOLERANCE, fuse, horizon_bucket
from .models import ALL_VARIABLES, OBSERVABLE_VARIABLES, ForecastSample
from .storage import Storage

# Same ground-truth priority as calibration (station > reanalysis):
# validating only against the archive while the fusion learns from stations
# would systematically mis-measure reliability.
_GT_RANK = {"station": 0, "model": 1}


def _best_truth_index(observations, kinds) -> dict:
    index: dict[tuple[str, datetime], float] = {}
    rank: dict[tuple[str, datetime], int] = {}
    for o in observations:
        rounded = o.time.replace(minute=0, second=0, microsecond=0)
        key = (o.variable, rounded)
        r = _GT_RANK.get(kinds.get(o.provider, "model"), 1)
        if key not in index or r < rank[key]:
            index[key] = o.value
            rank[key] = r
    return index


# Confidence bands reported in the reliability table.
BANDS = [
    (0.000, 0.900, "0.00-0.90"),
    (0.900, 0.950, "0.90-0.95"),
    (0.950, 0.990, "0.95-0.99"),
    (0.990, 0.999, "0.99-0.999"),
    (0.999, 1.001, "0.999-1.00"),
]


def is_hit(variable: str, value: float, truth: float) -> bool:
    """Error within tolerance; directions use the shortest angle."""
    tolerance = _VARIABLE_TOLERANCE.get(variable, 1.5)
    if variable == "wind_direction_10m":
        err = abs((value - truth + 180.0) % 360.0 - 180.0)
    else:
        err = abs(value - truth)
    return err <= tolerance


def replay(
    storage: Storage,
    config: Config,
    cell_key: str,
    split_ratio: float = 0.7,
    now: datetime | None = None,
) -> list[tuple[str, float, float, bool, float]]:
    """Score past forecasts the way production serves them.

    Stats are learned from the oldest `split_ratio` of the residual window
    with the production pipeline (build_stats: SQL aggregation, spatial
    interpolation, horizon priors); validation runs on the newest part, so
    the result is out-of-sample. For each horizon bucket, the latest issue
    of each provider at each valid time is fused, like the served run.
    Analysis rows (issued == valid) are truth proxies, not forecasts, and
    are left out. Returns (variable, confidence, hit, calibrated, bucket).
    """
    now = now or datetime.now(timezone.utc)
    since = now - timedelta(days=config.residual_window_days)
    cutoff = since + (now - since) * split_ratio
    stats = build_stats(storage, since, cutoff, now=now)
    kinds = {p.name: p.kind for p in storage.get_providers()}

    forecasts = storage.forecasts_in_window(
        [cell_key], list(ALL_VARIABLES), cutoff, now
    )
    observations = storage.observations_in_window(
        [cell_key], list(OBSERVABLE_VARIABLES), cutoff, now
    )
    obs_index = _best_truth_index(observations, kinds)

    latest: dict[tuple[float, str, str, datetime], ForecastSample] = {}
    for f in forecasts:
        if f.issued_at >= f.valid_at:
            continue
        key = (horizon_bucket(f.valid_at, f.issued_at), f.provider, f.variable, f.valid_at)
        if key not in latest or f.issued_at > latest[key].issued_at:
            latest[key] = f
    by_bucket: dict[float, list[ForecastSample]] = defaultdict(list)
    for key, f in latest.items():
        by_bucket[key[0]].append(f)

    scored: list[tuple[str, float, float, bool, float]] = []
    for bucket, samples in sorted(by_bucket.items()):
        for r in fuse(samples, stats, kinds, config, now):
            rounded = r.valid_at.replace(minute=0, second=0, microsecond=0)
            truth = obs_index.get((r.variable, rounded))
            if truth is None:
                continue
            hit = 1.0 if is_hit(r.variable, r.consensus, truth) else 0.0
            scored.append((r.variable, r.confidence, hit, r.calibrated, bucket))
    return scored


def verify(
    storage: Storage,
    config: Config,
    cell_key: str,
    split_ratio: float = 0.7,
    now: datetime | None = None,
) -> dict:
    """Reliability table: empirical hit rate per stated-confidence band.

    A reliable system has empirical hit rate ~ stated confidence in every
    band. See replay() for how the past is scored.
    """
    hits: dict[tuple[str, int], list[tuple[float, float]]] = defaultdict(list)
    calibrated_flags: dict[tuple[str, int], bool] = {}
    for variable, confidence, hit, calibrated, _bucket in replay(
        storage, config, cell_key, split_ratio, now
    ):
        band = _band_index(confidence)
        hits[(variable, band)].append((confidence, hit))
        calibrated_flags[(variable, band)] = calibrated
    return _build_report(hits, calibrated_flags)


def _band_index(confidence: float) -> int:
    for i, (lo, hi, _label) in enumerate(BANDS):
        if lo <= confidence < hi or (i == len(BANDS) - 1 and confidence >= lo):
            return i
    return 0


def _build_report(
    hits: dict[tuple[str, int], list[tuple[float, float]]],
    calibrated_flags: dict[tuple[str, int], bool],
) -> dict:
    report: dict[str, list] = {"bands": []}
    for band_idx, (_lo, _hi, label) in enumerate(BANDS):
        bucket: list[tuple[float, float]] = []
        for (var, band), entries in hits.items():
            if band == band_idx:
                bucket.extend(entries)
        if not bucket:
            continue
        n = len(bucket)
        avg_conf = sum(c for c, _ in bucket) / n
        emp = sum(h for _, h in bucket) / n
        report["bands"].append(
            {
                "band": label,
                "n": n,
                "avg_confidence": round(avg_conf, 4),
                "empirical_rate": round(emp, 4),
                "gap": round(emp - avg_conf, 4),
            }
        )
    return report


def report_by_variable(
    storage: Storage,
    config: Config,
    cell_key: str,
    split_ratio: float = 0.7,
    now: datetime | None = None,
) -> dict:
    """Reliability per variable, pooling all confidence bands."""
    per_var: dict[str, list[tuple[float, float]]] = defaultdict(list)
    for variable, confidence, hit, _cal, _bucket in replay(
        storage, config, cell_key, split_ratio, now
    ):
        per_var[variable].append((confidence, hit))

    out: dict[str, object] = {}
    for var, bucket in sorted(per_var.items()):
        n = len(bucket)
        avg_conf = sum(c for c, _ in bucket) / n
        emp = sum(h for _, h in bucket) / n
        out[var] = {
            "n": n,
            "avg_confidence": round(avg_conf, 4),
            "empirical_rate": round(emp, 4),
            "gap": round(emp - avg_conf, 4),
        }
    return out
