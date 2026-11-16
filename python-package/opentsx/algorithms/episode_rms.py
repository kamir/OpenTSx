"""Root mean square amplitude on a finite regular episode."""

import numpy as np

from opentsx.algorithms._analysis import record, regular_values


def episode_rms(episode):
    values, source = regular_values(episode, minimum=1, allow_constant=True)
    with np.errstate(over="raise", invalid="raise"):
        amplitude = float(np.sqrt(np.mean(values**2)))
    return record(
        "episode_rms",
        {},
        source,
        {"value": amplitude, "unit": source.get("unit")},
        [],
        backend={"name": "numpy", "version": np.__version__},
    )
