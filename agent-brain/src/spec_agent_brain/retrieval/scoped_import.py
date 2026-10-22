"""Scoped helper for importing third-party libraries without leaking proxy side effects."""
from contextlib import contextmanager
import os


@contextmanager
def safe_third_party_import():
    saved = {k: os.environ[k] for k in ("ALL_PROXY", "all_proxy") if k in os.environ}
    try:
        if any("socks" in v.lower() for v in saved.values()):
            try:
                import socksio  # type: ignore # noqa: F401
            except ImportError:
                for k in saved:
                    os.environ.pop(k, None)
        yield
    finally:
        os.environ.update(saved)
