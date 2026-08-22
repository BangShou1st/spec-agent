"""Shared test fixtures for the agent-brain test suite."""

from pathlib import Path

import pytest

from spec_agent_brain.config import Settings

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURES_DIR = REPO_ROOT / "contracts" / "fixtures"


def load_fixture(name: str) -> dict:
    import json

    return json.loads((FIXTURES_DIR / name).read_text(encoding="utf-8"))


@pytest.fixture()
def settings() -> Settings:
    return Settings(
        internal_secret="test-secret",
        model_mode="fake",
        broker_url="http://broker.invalid/internal/v1/model-inference",
        broker_timeout_seconds=5.0,
    )
