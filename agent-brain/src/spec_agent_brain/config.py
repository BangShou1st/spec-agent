"""Service configuration resolved from environment variables.

The brain never receives database credentials and never holds provider API
keys: model inference goes through the Java internal inference broker.
"""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    internal_secret: str
    model_mode: str  # "broker" | "fake"
    broker_url: str
    broker_timeout_seconds: float

    @property
    def auth_enabled(self) -> bool:
        return bool(self.internal_secret)


def load_settings() -> Settings:
    return Settings(
        internal_secret=os.environ.get("SPEC_AGENT_BRAIN_INTERNAL_SECRET", ""),
        model_mode=os.environ.get("SPEC_AGENT_BRAIN_MODEL_MODE", "fake"),
        broker_url=os.environ.get(
            "SPEC_AGENT_INTERNAL_BROKER_URL",
            "http://localhost:8080/internal/v1/model-inference",
        ),
        broker_timeout_seconds=float(os.environ.get("SPEC_AGENT_BROKER_TIMEOUT_SECONDS", "120")),
    )
