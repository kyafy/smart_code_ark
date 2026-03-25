"""Shared pytest fixtures for backend tests."""
import pytest


@pytest.fixture(scope="session")
def anyio_backend():
    return "asyncio"
