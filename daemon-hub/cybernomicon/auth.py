"""Bearer-token authentication for the Daemon Hub.

Phase I uses a single securely-generated shared token (see
`config.load_or_create_api_token`). The Android client sends it as
`Authorization: Bearer <token>`. Comparison is constant-time. TLS is handled at
the transport layer (uvicorn --ssl-*); this module only proves identity.

Multi-client tokens, rotation, and per-client scopes are a Phase VII concern;
the dependency boundary here won't change when that arrives.
"""
from __future__ import annotations

import secrets

from fastapi import Header, HTTPException, status


class TokenAuth:
    def __init__(self, token: str):
        self._token = token

    def verify(self, authorization: str | None) -> None:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="missing bearer token",
                headers={"WWW-Authenticate": "Bearer"})
        presented = authorization.split(" ", 1)[1].strip()
        if not secrets.compare_digest(presented, self._token):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="invalid token")

    def verify_value(self, token: str | None) -> bool:
        """For WebSocket query-param auth (no header available)."""
        return bool(token) and secrets.compare_digest(token, self._token)


def make_dependency(auth: TokenAuth):
    async def dependency(authorization: str | None = Header(default=None)):
        auth.verify(authorization)
    return dependency
