"""
Example test for FastAPI endpoints using pytest + httpx.
LLM should follow this pattern when generating tests for new modules.
"""
import pytest
from httpx import AsyncClient, ASGITransport
from unittest.mock import patch, MagicMock
from app.main import app


@pytest.fixture
def anyio_backend():
    return "asyncio"


@pytest.mark.anyio
async def test_health_check():
    """Health endpoint should return 200."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.get("/health")
    assert resp.status_code == 200


@pytest.mark.anyio
async def test_list_users_empty(monkeypatch):
    """GET /users should return empty list when no data."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        with patch("app.main.get_db") as mock_db:
            mock_session = MagicMock()
            mock_session.query.return_value.offset.return_value.limit.return_value.all.return_value = []
            mock_db.return_value = iter([mock_session])
            resp = await client.get("/users")
    assert resp.status_code == 200


@pytest.mark.anyio
async def test_create_user_validation():
    """POST /users with invalid data should return 422."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        resp = await client.post("/users", json={})
    assert resp.status_code == 422
