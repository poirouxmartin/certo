"""Lot 2 of the 2026-11 code audit: calibration correctness."""

import math
from datetime import datetime, timedelta, timezone

from sure_weather.calibration import build_stats, compute_residuals
from sure_weather.config import Config
from sure_weather.fusion import (
    ProviderStat,
    _VARIABLE_TOLERANCE,
    base_weight,
    provider_sigma,
)
from sure_weather.models import ForecastSample, Observation, Residual
from sure_weather.storage import Storage
from sure_weather.verify import is_hit, replay, verify

NOW = datetime(2026, 11, 17, 12, tzinfo=timezone.utc)
CELL = "48.9,2.4"


def _stat(variable, samples, bias, rmse, provider="m"):
    return ProviderStat(
        provider=provider,
        cell_key=CELL,
        variable=variable,
        horizon_h=3.0,
        samples=samples,
        bias=bias,
        rmse=rmse,
        updated_at=NOW,
    )


# ---- P3: wind direction is calibrated ----


def test_direction_residuals_learn_angular_error(tmp_path):
    storage = Storage(tmp_path / "w.db")
    forecasts, observations = [], []
    for i, (pred, obs) in enumerate([(350.0, 10.0), (10.0, 350.0), (100.0, 80.0)]):
        t = NOW - timedelta(hours=i + 1)
        forecasts.append(
            ForecastSample("m", CELL, "wind_direction_10m", t - timedelta(hours=2), t, pred)
        )
        observations.append(Observation("era5_seamless", CELL, "wind_direction_10m", t, obs))
    residuals = compute_residuals(forecasts, observations, {"m": "model"})
    assert sorted(r.predicted for r in residuals) == [-20.0, 20.0, 20.0]
    storage.insert_residuals(residuals)
    ((*_, n, bias, mse),) = storage.residual_bias_stats(NOW - timedelta(days=1))
    assert n == 3
    assert math.isclose(bias, 20.0 / 3)
    assert math.isclose(math.sqrt(mse), 20.0)  # was 0: nothing learned
    storage.close()


def test_old_direction_residuals_are_dropped_once(tmp_path):
    path = tmp_path / "w.db"
    storage = Storage(path)
    t = NOW - timedelta(hours=1)
    storage.insert_residuals(
        [
            # Old format: observation stored twice.
            Residual("m", CELL, "wind_direction_10m", t, 3.0, 270.0, 270.0),
            # New format: signed error against 0.
            Residual("m", CELL, "wind_direction_10m", t, 12.0, -20.0, 0.0),
            Residual("m", CELL, "temperature_2m", t, 3.0, 11.0, 10.0),
        ]
    )
    with storage.tx() as c:
        c.execute("PRAGMA user_version = 0")
    storage.close()
    storage = Storage(path)
    rows = storage.locked_execute("SELECT variable, observed FROM residuals ORDER BY variable")
    assert rows == [("temperature_2m", 10.0), ("wind_direction_10m", 0.0)]
    assert storage.locked_fetchone("PRAGMA user_version")[0] == 1
    storage.close()


# ---- P7: noise floor ----


def test_constant_bias_model_is_not_perfect():
    # rmse == |bias|: all the error is bias, sqrt(rmse^2 - bias^2) = 0.
    stat = _stat("temperature_2m", 100_000, 1.5, 1.5)
    sigma = provider_sigma(stat, "temperature_2m", "model", NOW)
    floor = 0.1 * _VARIABLE_TOLERANCE["temperature_2m"]
    assert math.isclose(sigma, floor, rel_tol=0.01)  # prior pseudo-samples add ~0.5 %


def test_no_weight_cliff_near_zero_rmse():
    # Dry-weather precipitation: rmse 0 and rmse 0.01 used to weigh 1 vs 300 000.
    a = base_weight(_stat("precipitation", 300, 0.0, 0.0), "model", 3.0, NOW)
    b = base_weight(_stat("precipitation", 300, 0.0, 0.01), "model", 3.0, NOW)
    assert math.isclose(a, b)


# ---- P6: one weight scale ----


def test_unlearned_weights_by_kind():
    assert base_weight(None, "model", 3.0, NOW, variable="temperature_2m") == 1.0
    assert base_weight(None, "station", 3.0, NOW, variable="temperature_2m") == 4.0


def test_learned_model_does_not_drown_stations():
    # 720 samples at rmse 1.0 weighed 720 against a station's 2.0 before.
    learned = base_weight(_stat("temperature_2m", 720, 0.0, 1.0), "model", 3.0, NOW)
    station = base_weight(None, "station", 3.0, NOW, variable="temperature_2m")
    assert learned < 2 * station
    assert learned > base_weight(None, "model", 3.0, NOW, variable="temperature_2m")


def test_weight_is_scale_free_across_variables():
    # Same relative error, same weight, whatever the unit.
    t = base_weight(_stat("temperature_2m", 500, 0.0, 1.0), "model", 3.0, NOW)
    v = base_weight(_stat("visibility", 500, 0.0, 2500.0), "model", 3.0, NOW)
    assert math.isclose(t, v)


def test_stale_stats_fall_back_to_prior():
    old = ProviderStat("m", CELL, "temperature_2m", 3.0, 720, 0.0, 0.5, NOW - timedelta(days=60))
    assert math.isclose(
        provider_sigma(old, "temperature_2m", "model", NOW),
        _VARIABLE_TOLERANCE["temperature_2m"],
        rel_tol=0.05,  # 60 days at an 8-day decay: 720 samples weigh ~0.4
    )


# ---- P10: verify replays production ----


def test_direction_hit_is_angular():
    assert is_hit("wind_direction_10m", 355.0, 5.0)
    assert not is_hit("wind_direction_10m", 300.0, 5.0)
    assert is_hit("temperature_2m", 11.5, 10.0)


def test_verify_scores_forecasts_not_analysis(tmp_path):
    storage = Storage(tmp_path / "w.db")
    storage.upsert_cells([(CELL, 48.9, 2.4)])
    cfg = Config(db_path=tmp_path / "w.db", residual_window_days=10)
    # Training part (oldest 70 %): residuals give model "m" a +1 bias.
    storage.insert_residuals(
        [
            Residual("m", CELL, "temperature_2m", NOW - timedelta(days=9, hours=h), 12.0, 11.0, 10.0)
            for h in range(10)
        ]
    )
    # Validation part: a 12 h forecast (+1 too warm, corrected to the truth)
    # and an analysis row that must not be scored.
    t = NOW - timedelta(days=1)
    storage.insert_forecasts(
        [
            ForecastSample("m", CELL, "temperature_2m", t - timedelta(hours=10), t, 11.0),
            ForecastSample("m", CELL, "temperature_2m", t + timedelta(hours=1), t + timedelta(hours=1), 50.0),
        ]
    )
    storage.insert_observations(
        [
            Observation("era5_seamless", CELL, "temperature_2m", t, 10.0),
            Observation("era5_seamless", CELL, "temperature_2m", t + timedelta(hours=1), 10.0),
        ]
    )
    scored = replay(storage, cfg, CELL, now=NOW)
    assert len(scored) == 1
    variable, confidence, hit, calibrated, bucket = scored[0]
    assert (variable, hit, calibrated, bucket) == ("temperature_2m", 1.0, True, 12.0)
    assert 0.0 < confidence < 1.0
    report = verify(storage, cfg, CELL, now=NOW)
    assert sum(b["n"] for b in report["bands"]) == 1
    # Same stats as the service would build over the training window.
    since = NOW - timedelta(days=10)
    stats = build_stats(storage, since, since + (NOW - since) * 0.7, now=NOW)
    assert stats[("m", CELL, "temperature_2m", 12.0)].bias == 1.0
    storage.close()
