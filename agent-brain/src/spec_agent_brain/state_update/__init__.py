"""文件名:__init__.py

用途:state_update 子包的对外出口,封装 brain 的 STATE_UPDATE 能力,并
统一导出处理入口与契约异常类型。
"""

from .engine import BrainContractError, handle_state_update

__all__ = ["BrainContractError", "handle_state_update"]
