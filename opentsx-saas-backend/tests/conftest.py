"""The required secrets for the test run, as values that exist only here. Set before any test imports the app."""

import os

os.environ["SECRET_KEY"] = "pytest-only-signing-key-0123456789abcdef"
os.environ["POSTGRES_PASSWORD"] = "pytest-only-database-password"
os.environ["FIRST_SUPERUSER_PASSWORD"] = "pytest-only-administrator-password"
