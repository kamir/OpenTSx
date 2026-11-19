"""requirements.txt holds what the backend runs with: each package once, and each one imported by app/ or loaded by
code named here."""

import ast
import re
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
# packages app/ does not import by name, and the code that loads them
LOADED_BY = {
    "uvicorn": "the server that runs app.main:app",
    "asyncpg": "the driver of postgresql+asyncpg (app/core/config.py)",
    "greenlet": "the async session of SQLAlchemy",
    "bcrypt": "the bcrypt scheme of passlib (app/core/security.py)",
    "email-validator": "EmailStr (app/schemas/user.py)",
    "python-dotenv": "the .env file of pydantic-settings and uvicorn --env-file",
}
IMPORTED_AS = {"python-jose": "jose", "pydantic-settings": "pydantic_settings"}


def requirements():
    lines = (HERE / "requirements.txt").read_text().splitlines()
    return [re.split(r"[\[=<>~ ]", line.strip())[0].lower() for line in lines if line.strip() and not line.startswith("#")]


def imported():
    names = set()
    for source in (HERE / "app").rglob("*.py"):
        for node in ast.walk(ast.parse(source.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Import):
                names.update(a.name.split(".")[0] for a in node.names)
            elif isinstance(node, ast.ImportFrom) and node.level == 0:
                names.add(node.module.split(".")[0])
    return names


def test_every_requirement_is_used():
    used = imported()
    unused = [r for r in requirements() if r not in LOADED_BY and IMPORTED_AS.get(r, r.replace("-", "_")) not in used]
    assert unused == []


def test_every_requirement_is_listed_once():
    names = requirements()
    assert sorted(n for n in set(names) if names.count(n) > 1) == []
