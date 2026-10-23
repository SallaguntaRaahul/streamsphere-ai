"""OIDC bearer validation: RS256 signature (JWKS), issuer and audience are always checked."""
import jwt
from fastapi import Depends, HTTPException, Request
from . import config

_jwks = jwt.PyJWKClient(config.JWKS_URI, cache_keys=True, lifespan=600)


def _decode(token: str) -> dict:
    try:
        key = _jwks.get_signing_key_from_jwt(token).key
        return jwt.decode(token, key, algorithms=["RS256"], audience=config.AUDIENCE, issuer=config.ISSUER,
                          options={"require": ["exp", "iss", "aud", "sub"]})
    except jwt.PyJWTError as e:
        raise HTTPException(status_code=401, detail="invalid token") from e


def optional_user(request: Request) -> dict | None:
    h = request.headers.get("authorization", "")
    if not h.lower().startswith("bearer "):
        return None
    return _decode(h[7:])


def require_user(claims: dict | None = Depends(optional_user)) -> dict:
    if claims is None:
        raise HTTPException(status_code=401, detail="authentication required")
    return claims


def require_service(claims: dict = Depends(require_user)) -> dict:
    if "service" not in claims.get("realm_access", {}).get("roles", []):
        raise HTTPException(status_code=403, detail="service role required")
    return claims
