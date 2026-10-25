"""Test proxy environment isolation, dependency-level SOCKS support, and zero process-wide side effects."""
import importlib
import os
from unittest.mock import patch

import httpx

from spec_agent_brain.model_client.broker_client import BrokerModelClient
from spec_agent_brain.retrieval.embedding import LocalEmbedding, fixed_profile
from spec_agent_brain.retrieval.service import HostStore


def test_no_proxy_environment_internal_clients():
    """Scenario 1: No proxy variables in environment."""
    clean_env = {
        "ALL_PROXY": None,
        "all_proxy": None,
        "HTTP_PROXY": None,
        "http_proxy": None,
        "HTTPS_PROXY": None,
        "https_proxy": None,
    }
    with patch.dict(os.environ, {k: v for k, v in clean_env.items() if v is not None}, clear=False):
        for k in clean_env:
            os.environ.pop(k, None)

        # Import must succeed seamlessly
        import spec_agent_brain
        import spec_agent_brain.retrieval.embedding
        importlib.reload(spec_agent_brain.retrieval.embedding)

        # Broker model client must enforce trust_env=False
        broker = BrokerModelClient("http://127.0.0.1:8080/broker", "secret")
        try:
            assert broker._client.trust_env is False
        finally:
            broker.close()

        # LocalEmbedding origin validation and profile binding
        embedding = LocalEmbedding("http://127.0.0.1:11434")
        assert embedding.profile.profile_id == fixed_profile().profile_id

        # HostStore RPC base formatting
        store = HostStore("http://127.0.0.1:8080", "secret")
        assert store.base == "http://127.0.0.1:8080/internal/v1/retrieval-store"


def test_socks_proxy_environment_and_zero_side_effects():
    """Scenario 2: SOCKS proxy present in environment; verified dependency-level resolution without environ mutation."""
    proxy_env = {
        "ALL_PROXY": "socks5://127.0.0.1:1080",
        "all_proxy": "socks5://127.0.0.1:1080",
        "HTTP_PROXY": "http://127.0.0.1:7890",
        "HTTPS_PROXY": "http://127.0.0.1:7890",
    }
    with patch.dict(os.environ, proxy_env):
        # Direct imports of ollama, langchain_ollama, and spec_agent_brain must succeed without ImportError
        import langchain_ollama
        import ollama
        import spec_agent_brain
        import spec_agent_brain.retrieval.configured_embedding
        import spec_agent_brain.retrieval.embedding

        importlib.reload(spec_agent_brain)
        importlib.reload(spec_agent_brain.retrieval.embedding)
        importlib.reload(spec_agent_brain.retrieval.configured_embedding)

        # Crucial invariant: Environment variables must be 100% preserved, never popped or deleted
        assert os.environ.get("ALL_PROXY") == "socks5://127.0.0.1:1080"
        assert os.environ.get("all_proxy") == "socks5://127.0.0.1:1080"
        assert os.environ.get("HTTP_PROXY") == "http://127.0.0.1:7890"
        assert os.environ.get("HTTPS_PROXY") == "http://127.0.0.1:7890"

        # Internal client must explicitly bypass proxies to reach local Ollama and local Java RPC
        broker = BrokerModelClient("http://127.0.0.1:8080/broker", "secret")
        try:
            assert broker._client.trust_env is False
        finally:
            broker.close()

        store = HostStore("http://127.0.0.1:8080", "secret")
        assert store.base == "http://127.0.0.1:8080/internal/v1/retrieval-store"

        embedding = LocalEmbedding("http://127.0.0.1:11434")
        assert embedding.profile.profile_id == fixed_profile().profile_id


def test_external_clients_can_use_environment_proxy():
    """Scenario 3: Standard external clients (e.g. for Gemini/Kilo) can read environment proxy normally."""
    proxy_env = {
        "HTTPS_PROXY": "http://127.0.0.1:7890",
    }
    with patch.dict(os.environ, proxy_env):
        default_client = httpx.Client()
        try:
            assert default_client.trust_env is True
        finally:
            default_client.close()
