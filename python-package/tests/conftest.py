import numpy as np
import pytest


@pytest.fixture
def rng():
    """Deterministic random generator so statistical assertions are reproducible."""
    return np.random.default_rng(42)
