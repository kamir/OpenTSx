"""Application configuration."""

from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import ValidationInfo, field_validator, validator

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
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    REFRESH_TOKEN_EXPIRE_DAYS: int = 7

    # Database
    POSTGRES_SERVER: str = "localhost"
    POSTGRES_USER: str = "opentsx"
    POSTGRES_PASSWORD: str
    POSTGRES_DB: str = "opentsx"
    POSTGRES_PORT: int = 5432

    # Redis
    REDIS_HOST: str = "localhost"
    REDIS_PORT: int = 6379
    REDIS_DB: int = 0

    # Email (for invitations)
    SMTP_TLS: bool = True
    SMTP_PORT: Optional[int] = None
    SMTP_HOST: Optional[str] = None
    SMTP_USER: Optional[str] = None
    SMTP_PASSWORD: Optional[str] = None
    EMAILS_FROM_EMAIL: str = "noreply@opentsx.com"
    EMAILS_FROM_NAME: str = "OpenTSx"

    # Stripe (payment stub)
    STRIPE_SECRET_KEY: str = "sk_test_stub"
    STRIPE_WEBHOOK_SECRET: str = "whsec_stub"

    # First superuser
    FIRST_SUPERUSER_EMAIL: str = "admin@opentsx.com"
    FIRST_SUPERUSER_PASSWORD: str

    # DAG runs (app/api/dags.py): the retrieval server that writes vector topics, and who may ask for it
    RETRIEVAL_URL: Optional[str] = None       # e.g. http://opentsx-retrieval:8080; unset: no runs
    DAG_RUN_TOKEN: Optional[str] = None       # sent as X-OpenTSx-Run-Token; must match the server's token
    DAG_RUN_ROLE: str = "superuser"           # "superuser": administrators only; "user": every signed-in user

    # Test operation: "admin" with the password "admin" signs in as the first administrator. For a lab only:
    # whoever reaches the page is an administrator then. The stored password of the account stays as it is.
    TEST_LOGIN: bool = False

    # Features
    ENABLE_SIGNUP: bool = True
    ENABLE_INVITATIONS: bool = True
    ENABLE_PAYMENTS: bool = False  # Stub for now

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

    @property
    def database_url(self) -> str:
        """Get database URL."""
        return f"postgresql+asyncpg://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}@{self.POSTGRES_SERVER}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"

    @property
    def sync_database_url(self) -> str:
        """Get synchronous database URL."""
        return f"postgresql+psycopg2://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}@{self.POSTGRES_SERVER}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"

    @property
    def redis_url(self) -> str:
        """Get Redis URL."""
        return f"redis://{self.REDIS_HOST}:{self.REDIS_PORT}/{self.REDIS_DB}"

    model_config = SettingsConfigDict(
        case_sensitive=True,
        env_file=".env",
        extra="ignore",  # Ignore extra environment variables from docker-compose
        hide_input_in_errors=True,
    )


settings = Settings()
