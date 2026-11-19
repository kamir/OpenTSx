"""FastAPI dependencies for authentication and database."""

from typing import Optional, AsyncGenerator
from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select

from app.db.session import get_db
from app.core.security import decode_token
from app.models.user import User

# auto_error=False, so a missing token gets the error text of get_current_user, not the one of FastAPI
security = HTTPBearer(auto_error=False)
NOT_VALID = "token: not valid or expired; sign in again"


async def get_current_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Depends(security),
    db: AsyncSession = Depends(get_db)
) -> User:
    """
    Get current authenticated user from JWT token.

    Args:
        credentials: HTTP Authorization header with Bearer token
        db: Database session

    Returns:
        User: Current authenticated user

    Raises:
        HTTPException: If token is invalid or user not found
    """
    if credentials is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization: no bearer token; sign in and send Authorization: Bearer <access token>",
            headers={"WWW-Authenticate": "Bearer"},
        )
    token = credentials.credentials

    # Decode token
    payload = decode_token(token)
    if payload is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=NOT_VALID,
            headers={"WWW-Authenticate": "Bearer"},
        )

    # Get user ID from token. A refresh token is not an access token.
    user_id = payload.get("sub")
    if user_id is None or payload.get("type") == "refresh" or not str(user_id).isdigit():
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail=NOT_VALID,
            headers={"WWW-Authenticate": "Bearer"},
        )

    # Query user from database
    result = await db.execute(select(User).where(User.id == int(user_id)))
    user = result.scalar_one_or_none()

    if user is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="token: the account no longer exists; sign in with an existing account",
            headers={"WWW-Authenticate": "Bearer"},
        )

    if not user.is_active:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="this account: deactivated; ask an administrator to activate it"
        )

    return user
