"""文件名:__init__.py

用途:artifact 子包的对外出口,封装 ARTIFACT_GENERATION 引擎——用一次
模型调用生成派生工件,并统一导出其处理入口与契约异常类型。
"""

from .engine import ArtifactBrainContractError as BrainContractError
from .engine import UngroundedReferenceError, handle_artifact

__all__ = ["ArtifactBrainContractError", "UngroundedReferenceError", "handle_artifact"]
