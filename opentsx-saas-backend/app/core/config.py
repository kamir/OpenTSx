"""Application configuration."""

from typing import Optional
from pydantic_settings import BaseSettings, SettingsConfigDict
from pydantic import validator


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
    SECRET_KEY: str = "your-secret-key-change-in-production-min-32-chars-long"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    REFRESH_TOKEN_EXPIRE_DAYS: int = 7

    # Database
    POSTGRES_SERVER: str = "localhost"
    POSTGRES_USER: str = "opentsx"
    POSTGRES_PASSWORD: str = "opentsx"
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
    FIRST_SUPERUSER_PASSWORD: str = "admin123"

    # Features
    ENABLE_SIGNUP: bool = True
    ENABLE_INVITATIONS: bool = True
    ENABLE_PAYMENTS: bool = False  # Stub for now

    @property
    def database_url(self) -> str:
        """Get database URL."""
        return f"postgresql+asyncpg://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}@{self.POSTGRES_SERVER}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"

    @property
    def sync_database_url(self) -> str:
        """Get synchronous database URL."""
        return f"postgresql://{self.POSTGRES_USER}:{self.POSTGRES_PASSWORD}@{self.POSTGRES_SERVER}:{self.POSTGRES_PORT}/{self.POSTGRES_DB}"

    @property
    def redis_url(self) -> str:
        """Get Redis URL."""
        return f"redis://{self.REDIS_HOST}:{self.REDIS_PORT}/{self.REDIS_DB}"

    model_config = SettingsConfigDict(
        case_sensitive=True,
        env_file=".env",
        extra="ignore"  # Ignore extra environment variables from docker-compose
    )


settings = Settings()
