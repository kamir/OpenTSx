"""The engines keep the bound parameters of a statement, such as a password hash, out of their error messages."""

from app.db.session import async_engine, sync_engine


def test_no_engine_writes_bound_parameters_into_its_errors():
    assert async_engine.sync_engine.hide_parameters
    assert sync_engine.hide_parameters
