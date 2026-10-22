"""文件名:__init__.py

用途:spec_agent_brain 包的入口,标识这是 Spec Agent V2 的 Python 决策引擎
(agent-brain 服务),并暴露包版本号。
"""

__version__ = "0.1.0"

import os

for _k in ("ALL_PROXY", "all_proxy"):
    if _k in os.environ and "socks" in os.environ[_k].lower():
        try:
            import socksio  # type: ignore # noqa: F401
        except ImportError:
            os.environ.pop(_k, None)
