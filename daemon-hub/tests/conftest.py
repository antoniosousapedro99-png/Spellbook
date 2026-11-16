"""Shared test fixtures.

Sets an isolated data directory + fixed token *before* the package is imported,
so the module-level FastAPI app and every Database land in a throwaway temp dir.
"""
import os
import tempfile

# Must run before any `cybernomicon` import pulls in config/main.
_TMP = tempfile.mkdtemp(prefix="cybernomicon-test-")
os.environ["CYBERNOMICON_DATA_DIR"] = _TMP
os.environ["CYBERNOMICON_API_TOKEN"] = "test-token-123"

import pytest  # noqa: E402

from cybernomicon.config import load_settings  # noqa: E402
from cybernomicon.db import Database  # noqa: E402
from cybernomicon.events import EventBroadcaster  # noqa: E402


@pytest.fixture
def db(tmp_path):
    database = Database(tmp_path / "test.db")
    yield database
    database.close()


@pytest.fixture
def events():
    return EventBroadcaster()


@pytest.fixture
def settings():
    return load_settings()
