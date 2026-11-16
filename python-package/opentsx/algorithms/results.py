"""Turn DFA and event-synchronization output into v2 ``AnalysisResult`` records."""

from __future__ import annotations

from itertools import combinations
from typing import Mapping, Optional, Sequence

import numpy as np

from opentsx.algorithms.dfa import DFA
from opentsx.algorithms.event_sync import EventSynchronization
from opentsx.model import episodes as ep
from opentsx.model.analysis import analysis_result, curve, matrix, scalar

DFA_VERSION = "1.0.0"
ES_VERSION = "1.0.0"


def dfa_result(dfa: DFA, episode: Mapping, *, source: str, created_at_us: Optional[int] = None,
               pipeline: Optional[str] = None, **calculate_kwargs) -> dict:
    """Runs DFA on one episode. alpha is fitted over all computed scales; F(s) is kept as a curve."""
    out = dfa.analyze(ep.values(episode), **calculate_kwargs)
    scales = [float(s) for s in out["scales"]]
    params = {"order": int(dfa.polynom_order), "scales": scales}
    return analysis_result(
        "dfa", DFA_VERSION, params, [episode["episodeId"]], [episode["series"]["seriesId"]],
        episode["tStart"], episode["tEnd"],
        scalars=[scalar("alpha", out["alpha"], fit_from=min(scales), fit_to=max(scales)),
                 scalar("intercept", out["intercept"]),
                 scalar("r_squared", out["r_squared"])],
        curves=[curve("fluctuation", "scale", scales, out["fluctuations"], x_unit="samples",
                      unit=episode["series"]["unit"])],
        source=source, created_at_us=created_at_us, pipeline=pipeline)


def event_sync_result(es: EventSynchronization, episodes: Sequence[Mapping], *, threshold_method: str = "std",
                      source: str, created_at_us: Optional[int] = None, pipeline: Optional[str] = None) -> dict:
    """Event synchronization over n episodes as two n x n matrices over the series ids.

    ``Q`` is symmetric. ``q_directed`` holds q_ij - q_ji, positive when row i leads column j. The diagonal is NaN.
    """
    if len(episodes) < 2:
        raise ValueError("event synchronization needs at least two episodes")
    keys = [e["series"]["seriesId"] for e in episodes]
    n = len(episodes)
    q = np.full((n, n), np.nan)
    delay = np.full((n, n), np.nan)
    for i, j in combinations(range(n), 2):
        out = es.analyze(ep.values(episodes[i]), ep.values(episodes[j]), method=threshold_method)
        q[i, j] = q[j, i] = out["overall_sync"]
        delay[i, j] = out["delay"]
        delay[j, i] = -out["delay"]
    params = {"threshold": threshold_method}
    if es.tau_max is not None:
        params["tauMax"] = float(es.tau_max)
    return analysis_result(
        "es", ES_VERSION, params, [e["episodeId"] for e in episodes], keys,
        min(e["tStart"] for e in episodes), max(e["tEnd"] for e in episodes),
        matrices=[matrix("Q", keys, keys, q.ravel()), matrix("q_directed", keys, keys, delay.ravel())],
        source=source, created_at_us=created_at_us, pipeline=pipeline)
