"""The engine keeps the bound parameters of a statement, such as a password hash, out of its error messages. The
backend has one engine, the async one; the tables come from create_all at start."""

from app.db import session


def test_the_engine_writes_no_bound_parameters_into_its_errors():
    assert session.async_engine.sync_engine.hide_parameters


def test_there_is_no_second_engine():
    assert not hasattr(session, "sync_engine")
    assert not hasattr(session, "SessionLocal")
