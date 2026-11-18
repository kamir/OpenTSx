"""The page the backend serves at / (static/index.html)."""

from fastapi.testclient import TestClient

from app.main import app


def test_the_login_form_is_not_prefilled():
    page = TestClient(app).get("/")
    assert page.status_code == 200
    assert 'id="password"' in page.text
    assert "admin123" not in page.text
    assert 'value="admin@localhost"' not in page.text
