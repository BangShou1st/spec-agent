"""文件名:test_config_fail_closed.py

用途:Brain 配置 fail-closed 的回归(R7):
- 未知/拼错的 model_mode 必须在启动加载时失败,不能静默运行 fake;
- broker 模式要求完整的 broker 配置(合法 URL + 非空内部密钥);
- 显式 fake 与合法 broker 是仅有的两个合法值;
- 健康检查真实反映配置与就绪状态(config_error 时不再报 ok)。
"""

import pytest

from spec_agent_brain.config import ConfigurationError, load_settings
from spec_agent_brain.app import create_app


def _env(monkeypatch, **overrides):
    base = {
        "SPEC_AGENT_BRAIN_MODEL_MODE": "broker",
        "SPEC_AGENT_INTERNAL_BROKER_URL": "http://localhost:8080/internal/v1/model-inference",
        "SPEC_AGENT_BRAIN_INTERNAL_SECRET": "test-secret",
    }
    base.update(overrides)
    for key, value in base.items():
        monkeypatch.setenv(key, value)


def test_typo_mode_fails_at_startup(monkeypatch):
    _env(monkeypatch, SPEC_AGENT_BRAIN_MODEL_MODE="brokre")
    with pytest.raises(ConfigurationError) as exc:
        load_settings()
    assert "must be one of" in str(exc.value)


def test_unknown_mode_fails_at_startup(monkeypatch):
    _env(monkeypatch, SPEC_AGENT_BRAIN_MODEL_MODE="real-model-please")
    with pytest.raises(ConfigurationError):
        load_settings()


def test_broker_without_secret_fails_at_startup(monkeypatch):
    _env(monkeypatch, SPEC_AGENT_BRAIN_INTERNAL_SECRET="")
    with pytest.raises(ConfigurationError) as exc:
        load_settings()
    assert "INTERNAL_SECRET" in str(exc.value)


def test_broker_with_invalid_url_fails_at_startup(monkeypatch):
    _env(monkeypatch, SPEC_AGENT_INTERNAL_BROKER_URL="not-a-url")
    with pytest.raises(ConfigurationError) as exc:
        load_settings()
    assert "SPEC_AGENT_INTERNAL_BROKER_URL" in str(exc.value)


def test_explicit_fake_is_a_valid_offline_mode(monkeypatch):
    _env(monkeypatch, SPEC_AGENT_BRAIN_MODEL_MODE="fake")
    settings = load_settings()
    assert settings.model_mode == "fake"


def test_valid_broker_mode_loads(monkeypatch):
    _env(monkeypatch)
    settings = load_settings()
    assert settings.model_mode == "broker"
    assert settings.internal_secret == "test-secret"


def test_broker_mode_request_is_rejected_without_live_broker(monkeypatch):
    """显式 broker 模式下,broker 不可达必须表现为 502,而不是 fake 结果。"""
    _env(monkeypatch)
    settings = load_settings()
    app = create_app(settings)
    from pathlib import Path
    from unittest.mock import patch

    from fastapi.testclient import TestClient

    from spec_agent_brain.model_client import ModelClientError
    from spec_agent_brain.model_client.broker_client import BrokerModelClient

    fixture = Path(__file__).resolve().parents[2] / "contracts" / "fixtures" / "agent-input-valid.json"
    import json

    payload = json.loads(fixture.read_text(encoding="utf-8"))
    client = TestClient(app)
    with patch.object(BrokerModelClient, "complete",
                      side_effect=ModelClientError("inference broker unreachable")):
        response = client.post(
            "/v1/decisions",
            json=payload,
            headers={"X-Spec-Agent-Internal-Token": "test-secret"},
        )
    assert response.status_code == 502
    assert response.json()["detail"].startswith("brain_failure:")


def test_health_reports_config_error_for_bad_manual_settings():
    from fastapi.testclient import TestClient
    from spec_agent_brain.config import Settings

    app = create_app(Settings(
        internal_secret="secret",
        model_mode="brokre",
        broker_url="http://localhost:8080/internal/v1/model-inference",
        broker_timeout_seconds=5.0,
    ))
    client = TestClient(app)
    body = client.get("/health").json()
    assert body["status"] == "config_error"
    assert body["ready"] is False
    assert body["configError"] == "unknown model mode"
    assert body["modelMode"] == "brokre"


def test_health_reports_ready_for_valid_fake_settings():
    from fastapi.testclient import TestClient
    from spec_agent_brain.config import Settings

    app = create_app(Settings(
        internal_secret="",
        model_mode="fake",
        broker_url="http://localhost:8080/internal/v1/model-inference",
        broker_timeout_seconds=5.0,
    ))
    client = TestClient(app)
    body = client.get("/health").json()
    assert body["status"] == "ok"
    assert body["ready"] is True
    assert body["configError"] is None
    assert body["modelMode"] == "fake"
