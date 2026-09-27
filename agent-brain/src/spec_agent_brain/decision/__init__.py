"""文件名:__init__.py

用途:decision 子包的对外出口,封装 brain 的 DECISION 能力,并统一导出
处理入口与各契约异常类型。
"""

from .engine import (
    ActionIneligibleBrainError,
    AmbiguousSourceRefsError,
    BrainContractError,
    UngroundedReferenceError,
    handle_decision,
)

__all__ = [
    "ActionIneligibleBrainError",
    "AmbiguousSourceRefsError",
    "BrainContractError",
    "UngroundedReferenceError",
    "handle_decision",
]
