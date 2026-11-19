"""The page the backend serves at /: what the service is and where its API and the web application are. No sign-in."""

import re

from fastapi.testclient import TestClient

from app.main import app


def test_the_page_names_the_api_and_the_web_application():
    page = TestClient(app).get("/")
    assert page.status_code == 200
    assert 'href="/docs"' in page.text
    assert 'href="http://localhost:3000"' in page.text


def test_the_page_has_no_sign_in_and_no_fixed_account():
    page = TestClient(app).get("/").text
    assert "mock-token" not in page
    assert 'id="password"' not in page
    assert "admin@localhost" not in page
    assert "localStorage" not in page
    assert "/api/v1/flows" not in page


def test_the_page_names_every_route_under_the_api_that_needs_no_token():
    spec = app.openapi()
    open_routes = {path.rsplit("/", 1)[1] for path, operations in spec["paths"].items()
                   if path.startswith("/api/v1/") and not any("security" in op for op in operations.values())}
    open_routes.add(app.openapi_url.rsplit("/", 1)[1])
    clause = re.search(r"except (.+?) needs the access token", TestClient(app).get("/").text, re.S).group(1)
    assert sorted(name for name in open_routes if name not in clause) == []
