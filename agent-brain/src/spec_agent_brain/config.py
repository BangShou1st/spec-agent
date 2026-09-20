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
    # strip(): `set X=value && next` in cmd.exe leaks the separator space into
    # the value; a trailing space once made the secret mismatch (401 on every
    # decision call) and silently disabled broker mode. Normalise at the door.
    return Settings(
        internal_secret=os.environ.get("SPEC_AGENT_BRAIN_INTERNAL_SECRET", "").strip(),
        model_mode=os.environ.get("SPEC_AGENT_BRAIN_MODEL_MODE", "fake").strip(),
        broker_url=os.environ.get(
            "SPEC_AGENT_INTERNAL_BROKER_URL",
            "http://localhost:8080/internal/v1/model-inference",
        ).strip(),
        # Keep in sync with the Java side (spec.agent.brain.read-timeout-seconds):
        # killing the broker call early wastes a completed model response, and
        # slow provider days have been observed at 128-208s per round-trip.
        broker_timeout_seconds=float(os.environ.get("SPEC_AGENT_BROKER_TIMEOUT_SECONDS", "300")),
    )
