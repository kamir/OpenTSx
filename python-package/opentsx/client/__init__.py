"""Client for the OpenTSx retrieval REST API (``opentsx-retrieval`` RetrievalServer)."""
from .retrieval import RetrievalClient, RetrievalError

__all__ = ["RetrievalClient", "RetrievalError"]
