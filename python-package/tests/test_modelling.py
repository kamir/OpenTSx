import copy
import importlib
import math

import pytest


def request(family="arima"):
    return {
        "schema": "opentsx.modelling-request.v1",
        "target": {
            "series_id": "price-a",
            "label": "Price",
            "unit": "EUR/MWh",
            "time_kind": "absolute",
            "episode_ids": ["episode-a"],
            "values": [2 + math.sin(i * 0.2) + i * 0.01 for i in range(64)],
            "timestamps_micros": [1_700_000_000_000_000 + i * 900_000_000 for i in range(64)],
        },
        "configuration": {
            "family": family,
            "order": [0, 0, 0],
            "seasonal_order": [0, 0, 0, 0],
            "trend": "n",
            "steps": 4,
            "alpha": 0.1,
            "maxiter": 200,
        },
        "backtest": {
            "initial": 48,
            "horizon": 4,
            "step": 4,
            "window": None,
            "seasonal_period": 4,
            "methods": ["naive", "mean", "drift", "seasonal_naive"],
        },
        "context": None,
    }


def api():
    return importlib.import_module("opentsx.client.modelling")


def test_real_forecast_baselines_backtests_and_axis_identity():
    raw = request()
    result = api().execute_modelling(raw)
    assert result["schema"] == "opentsx.modelling-result.v1"
    assert result["source"]["series_id"] == "price-a"
    assert result["source"]["unit"] == "EUR/MWh"
    assert result["source"]["episode_ids"] == ["episode-a"]
    assert len(result["forecast"]["result"]["mean"]) == 4
    assert (
        result["forecast"]["result"]["timestamps_micros"][0]
        == raw["target"]["timestamps_micros"][-1] + 900_000_000
    )
    assert set(result["backtest"]["result"]["metrics"]) == {
        "arima",
        "naive",
        "mean",
        "drift",
        "seasonal_naive",
    }
    assert result["baselines"]["naive"]["result"]["mean"] == [raw["target"]["values"][-1]] * 4
    assert all(f["training_stop"] == f["test_start"] for f in result["backtest"]["result"]["folds"])
    assert result["run_id"] == api().execute_modelling(raw)["run_id"]


@pytest.mark.parametrize(
    "field,value",
    [
        ("steps", 97),
        ("alpha", 0),
        ("maxiter", 201),
        ("order", [4, 0, 0]),
        ("seasonal_order", [2, 1, 2, 96]),
    ],
)
def test_rejects_unbounded_configuration_before_fitting(field, value):
    raw = request()
    raw["configuration"][field] = value
    with pytest.raises(ValueError):
        api().validate_modelling(raw)


@pytest.mark.parametrize(
    "change",
    [
        "nan",
        "irregular",
        "flagged",
        "different_lengths",
        "too_many_folds",
        "missing_naive",
        "unknown_field",
        "unknown_schema",
        "context_required",
    ],
)
def test_rejects_bad_requests(change):
    raw = request()
    if change == "nan":
        raw["target"]["values"][0] = float("nan")
    if change == "irregular":
        raw["target"]["timestamps_micros"][2] += 1
    if change == "flagged":
        raw["target"]["quality"] = [1] * 64
    if change == "different_lengths":
        raw["target"]["values"].pop()
    if change == "too_many_folds":
        raw["backtest"].update(initial=8, step=1)
    if change == "missing_naive":
        raw["backtest"]["methods"] = ["mean"]
    if change == "unknown_field":
        raw["surprise"] = "no"
    if change == "unknown_schema":
        raw["schema"] = "no"
    if change == "context_required":
        raw["configuration"]["family"] = "sarimax"
    with pytest.raises(ValueError):
        api().validate_modelling(raw)


def with_context():
    raw = request("sarimax")
    raw["configuration"]["order"] = [0, 0, 0]
    raw["context"] = {
        "names": ["known-calendar"],
        "historical": [[1 + i / 64] for i in range(64)],
        "historical_available_at_micros": [[0] for _ in range(64)],
        "future": [[2 + i / 64] for i in range(4)],
        "future_available_at_micros": [[0] for _ in range(4)],
    }
    return raw


def test_rejects_context_that_was_unknown_at_an_earlier_forecast_origin():
    raw = with_context()
    raw["context"]["historical_available_at_micros"][48][0] = raw["target"]["timestamps_micros"][48]
    with pytest.raises(ValueError, match="available"):
        api().validate_modelling(raw)


def test_rejects_future_context_unavailable_at_final_forecast_origin():
    raw = with_context()
    raw["context"]["future_available_at_micros"][0][0] = raw["target"]["timestamps_micros"][-1] + 1
    with pytest.raises(ValueError, match="available"):
        api().validate_modelling(raw)


def test_real_sarimax_uses_context_and_retains_reference_hashes():
    raw = with_context()
    result = api().execute_modelling(raw)
    assert result["forecast"]["method"] == "sarimax"
    assert result["forecast"]["input"]["training_exog_sha256"]
    assert result["forecast"]["input"]["future_exog_sha256"]
    assert result["source"]["context_names"] == ["known-calendar"]


def test_declared_context_units_produce_a_live_schema():
    raw = with_context()
    raw["context"]["units"] = ["1"]
    result = api().execute_modelling(raw)
    schema = result["context_schema"]
    assert schema["names"] == ["known-calendar"]
    assert schema["units"] == ["1"]
    assert schema["target_series_id"] == raw["target"]["series_id"]
    assert schema["policy"] == "immutable_known_ahead"


@pytest.mark.parametrize("units", [[], [""], ["1", "MW"], [None]])
def test_rejects_invalid_declared_context_units(units):
    raw = with_context()
    raw["context"]["units"] = units
    with pytest.raises(ValueError):
        api().validate_modelling(raw)


def test_real_sarima_and_no_input_mutation():
    raw = request("sarima")
    raw["configuration"].update(order=[0, 0, 0], seasonal_order=[1, 0, 0, 4])
    original = copy.deepcopy(raw)
    result = api().execute_modelling(raw)
    assert result["forecast"]["method"] == "sarima"
    assert raw == original


def test_reports_missing_quality_and_relative_axes_honestly():
    raw = request()
    raw["target"]["time_kind"] = "relative"
    raw["target"]["timestamps_micros"] = [i * 1_000_000 for i in range(64)]
    result = api().execute_modelling(raw)
    assert result["source"]["time_kind"] == "relative"
    assert any("quality" in w.lower() for w in result["warnings"])
    assert "conditional_gaussian" in result["forecast"]["result"]["interval_status"]


def test_rejects_an_explicit_null_quality_mask_before_fitting():
    raw = request()
    raw["target"].update(quality=[0] * 64, quality_known=None)
    with pytest.raises(ValueError, match="quality_known"):
        api().validate_modelling(raw)


@pytest.mark.parametrize("reference", [{}, []])
def test_rejects_non_string_episode_references_at_the_boundary(reference):
    raw = request()
    raw["target"]["episode_ids"] = [reference]
    with pytest.raises(ValueError, match="episode"):
        api().validate_modelling(raw)


@pytest.mark.parametrize(
    "order,seasonal,expected",
    [([3, 0, 0], [2, 0, 0, 64], 131), ([0, 0, 3], [0, 0, 2, 64], 132)],
)
def test_rejects_multiplicative_state_sizes_above_the_real_budget(order, seasonal, expected):
    from statsmodels.tsa.statespace.sarimax import SARIMAX

    raw = request("sarima")
    raw["target"]["values"] = [2 + math.sin(i * 0.2) + i * 0.01 for i in range(256)]
    raw["target"]["timestamps_micros"] = [
        1_700_000_000_000_000 + i * 900_000_000 for i in range(256)
    ]
    raw["configuration"].update(order=order, seasonal_order=seasonal)
    raw["backtest"]["initial"] = 240
    process = SARIMAX(raw["target"]["values"], order=order, seasonal_order=seasonal)
    assert process.k_states == expected
    with pytest.raises(ValueError, match="128-state"):
        api().validate_modelling(raw)


@pytest.mark.parametrize("placement", ["target", "alpha", "context"])
def test_rejects_oversized_json_integers_as_boundary_errors(placement):
    raw = with_context() if placement == "context" else request()
    if placement == "target":
        raw["target"]["values"][0] = 10**400
    elif placement == "alpha":
        raw["configuration"]["alpha"] = 10**400
    else:
        raw["context"]["historical"][0][0] = 10**400
    with pytest.raises(ValueError, match="finite"):
        api().validate_modelling(raw)


def test_missing_quality_availability_is_reported_even_when_bytes_are_zero():
    raw = request()
    raw["target"]["quality"] = [0] * 64
    result = api().execute_modelling(raw)
    assert any("quality" in message.lower() for message in result["warnings"])


class FakeConnection:
    def __init__(self, ready=True, answer=(200, {"ok": True}), eof=False):
        self.ready = ready
        self.answer = answer
        self.eof = eof
        self.closed = False

    def poll(self, timeout):
        assert timeout == 60
        return self.ready

    def recv(self):
        if self.eof:
            raise EOFError
        return self.answer

    def close(self):
        self.closed = True


class FakeProcess:
    def __init__(self):
        self.started = False
        self.alive = False
        self.terminated = False
        self.joined = False

    def start(self):
        self.started = True
        self.alive = True

    def is_alive(self):
        return self.alive

    def terminate(self):
        self.terminated = True
        self.alive = False

    def join(self):
        self.joined = True


class FakeContext:
    def __init__(self, receiver):
        self.receiver = receiver
        self.sender = FakeConnection()
        self.process = FakeProcess()

    def Pipe(self, *, duplex):
        assert duplex is False
        return self.receiver, self.sender

    def Process(self, *, target, args, daemon):
        assert target is api()._worker
        assert args[1] is self.sender
        assert daemon is True
        return self.process


def assert_gate_released():
    assert api()._GATE.acquire(blocking=False)
    api()._GATE.release()


def test_busy_gate_rejects_a_second_run_without_spawning(monkeypatch):
    def unexpected_context(*args):
        raise AssertionError("a busy service must not create another process")

    monkeypatch.setattr(api().multiprocessing, "get_context", unexpected_context)
    assert api()._GATE.acquire(blocking=False)
    try:
        status, body = api().run_modelling(request())
        assert status == 503
        assert "active" in body["error"]
    finally:
        api()._GATE.release()
    assert_gate_released()


def test_timeout_terminates_and_joins_the_process_then_releases_the_gate(monkeypatch):
    context = FakeContext(FakeConnection(ready=False))
    monkeypatch.setattr(api().multiprocessing, "get_context", lambda name: context)
    status, body = api().run_modelling(request())
    assert status == 504
    assert "60-second" in body["error"]
    assert context.process.started and context.process.terminated and context.process.joined
    assert context.receiver.closed and context.sender.closed
    assert_gate_released()


@pytest.mark.parametrize("eof,status", [(False, 200), (True, 422)])
def test_result_or_eof_closes_process_resources_and_releases_the_gate(monkeypatch, eof, status):
    context = FakeContext(FakeConnection(eof=eof))
    monkeypatch.setattr(api().multiprocessing, "get_context", lambda name: context)
    actual_status, body = api().run_modelling(request())
    assert actual_status == status
    assert body == (
        {"error": "The numerical process ended without a result."} if eof else {"ok": True}
    )
    assert context.process.joined and context.process.terminated
    assert context.receiver.closed and context.sender.closed
    assert_gate_released()
