"""Application configuration."""

from typing import Literal, Optional
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import AnyHttpUrl, TypeAdapter, ValidationError, ValidationInfo, field_validator, model_validator

PASSWORD_MAX_BYTES = 72  # bcrypt reads no further
PUBLISHED_EXAMPLES = frozenset({
    "your-secret-key-change-in-production-min-32-chars-long",
    "your-secret-key-change-this-in-production-use-random-32-chars",
    "change-this-to-a-secure-random-key-in-production",
    "your-secret-key-here-change-in-production",
    "your-secret-key-change-this-in-production",
    "your-secret-key-change-in-production",
    "change_this_password",
    "change_this_redis_password",
    "dev_password_123",
    "your_password",
    "SecurePassword123!",
    "SecurePass123!",
    "OldPassword123!",
    "NewSecurePassword456!",
})
HTTP_URL = TypeAdapter(AnyHttpUrl)


class Settings(BaseSettings):
    """Application settings."""

    # API
    API_V1_STR: str = "/api/v1"
    PROJECT_NAME: str = "OpenTSx SaaS"
    VERSION: str = "1.0.0"
    DESCRIPTION: str = "Visual Time Series Analysis Platform"

    # CORS
    BACKEND_CORS_ORIGINS: list[str] = ["http://localhost:3000", "http://localhost:8000"]

    # Security
    SECRET_KEY: str
    ALGORITHM: Literal["HS256", "HS384", "HS512"] = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30

    # Database
    POSTGRES_SERVER: str = "localhost"
    POSTGRES_USER: str = "opentsx"
    POSTGRES_PASSWORD: str
    POSTGRES_DB: str = "opentsx"
    POSTGRES_PORT: int = 5432

    # First superuser
    FIRST_SUPERUSER_EMAIL: str = "admin@opentsx.com"
    FIRST_SUPERUSER_PASSWORD: str

    # DAG runs (app/api/dags.py): the retrieval server that writes vector topics, and who may ask for it
    RETRIEVAL_URL: Optional[str] = None       # e.g. http://opentsx-retrieval:8080; unset or empty: no runs
    DAG_RUN_TOKEN: Optional[str] = None       # sent as X-OpenTSx-Run-Token; must match the server's token
    DAG_RUN_ROLE: Literal["superuser", "user"] = "superuser"  # "superuser": administrators only; "user": every account

    # Test operation: "admin" with the password "admin" signs in as the first administrator. For a lab only:
    # whoever reaches the page is an administrator then. The stored password of the account stays as it is.
    TEST_LOGIN: bool = False

    # false: POST /auth/register answers 403
    ENABLE_SIGNUP: bool = True

    # the level of the root logger; app/logging.conf sets the format and the levels of uvicorn, sqlalchemy and passlib
    LOG_LEVEL: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    @model_validator(mode="before")
    @classmethod
    def only_known_keys(cls, data):
        unknown = sorted(key for key in data if key not in cls.model_fields) if isinstance(data, dict) else []
        if unknown:
            raise ValueError(f"{', '.join(unknown)}: not a setting of the backend; remove it from .env, "
                             f"the settings are {', '.join(cls.model_fields)}")
        return data

    @field_validator("SECRET_KEY")
    @classmethod
    def secret_key_is_strong(cls, value: str) -> str:
        if value in PUBLISHED_EXAMPLES:
            raise ValueError("SECRET_KEY: published example value; generate a random key of at least 32 characters")
        if len(value) < 32:
            raise ValueError("SECRET_KEY: shorter than 32 characters; set at least 32 random characters")
        return value

    @field_validator("POSTGRES_PASSWORD", "FIRST_SUPERUSER_PASSWORD")
    @classmethod
    def password_is_strong(cls, value: str, info: ValidationInfo) -> str:
        name = info.field_name
        if value in PUBLISHED_EXAMPLES:
            raise ValueError(f"{name}: published example value; set a random password of at least 12 characters")
        if len(value) < 12:
            raise ValueError(f"{name}: shorter than 12 characters; set at least 12 characters")
        if name == "FIRST_SUPERUSER_PASSWORD" and len(value.encode("utf-8")) > PASSWORD_MAX_BYTES:
            raise ValueError(f"{name}: longer than 72 bytes; set at most 72 bytes in UTF-8")
        return value

    @field_validator("RETRIEVAL_URL")
    @classmethod
    def retrieval_url_is_http(cls, value: Optional[str]) -> Optional[str]:
        if not value:
            return None
        try:
            HTTP_URL.validate_python(value)
        except ValidationError as e:
            raise ValueError("RETRIEVAL_URL: not an http or https address; set the address of the retrieval server "
                             "such as http://opentsx-retrieval:8080") from e
        return value

    @field_validator("BACKEND_CORS_ORIGINS")
    @classmethod
    def no_wildcard_origin(cls, value: list[str]) -> list[str]:
        if "*" in value:
            raise ValueError('BACKEND_CORS_ORIGINS: "*" is not allowed, the backend accepts credentials from these '
                             "origins; list each origin such as http://localhost:3000")
        return value

    @field_validator("LOG_LEVEL", mode="before")
    @classmethod
    def log_level_in_capitals(cls, value):
        return value.upper() if isinstance(value, str) else value

    @property
    def database_url(self) -> str:
        """Get database URL."""
        return f"postgresql+asyncpg://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}@{self.POSTGRES_SERVER}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"

    model_config = SettingsConfigDict(
        case_sensitive=True,
        env_file=".env",
        extra="forbid",
        hide_input_in_errors=True,
    )


settings = Settings()
