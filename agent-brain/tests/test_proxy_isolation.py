"""Test proxy environment isolation and absence of global side effects."""
import importlib
import os
from unittest.mock import patch

from spec_agent_brain.model_client.broker_client import BrokerModelClient
from spec_agent_brain.retrieval.service import HostStore


def test_import_preserves_proxy_environment():
    proxy_val = "http://127.0.0.1:7890"
    with patch.dict(os.environ, {
        "ALL_PROXY": proxy_val,
        "all_proxy": proxy_val,
        "HTTP_PROXY": proxy_val,
        "HTTPS_PROXY": proxy_val,
    }):
        import spec_agent_brain
        importlib.reload(spec_agent_brain)
        assert os.environ.get("ALL_PROXY") == proxy_val
        assert os.environ.get("all_proxy") == proxy_val
        assert os.environ.get("HTTP_PROXY") == proxy_val
        assert os.environ.get("HTTPS_PROXY") == proxy_val


def test_internal_clients_disable_trust_env():
    # BrokerModelClient default client must have trust_env=False
    broker = BrokerModelClient("http://127.0.0.1:8080/broker", "secret")
    try:
        assert broker._client.trust_env is False
    finally:
        broker.close()

    # HostStore internal RPC base formatting and trust_env configuration
    store = HostStore("http://127.0.0.1:8080", "secret")
    assert store.base == "http://127.0.0.1:8080/internal/v1/retrieval-store"
