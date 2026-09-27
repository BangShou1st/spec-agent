"""文件名:artifacts.py

用途:工件生成边界的严格 Pydantic 契约(Python -> Spring)。

工件响应有自己独立的协议版本:工件是派生的、只读的交付物
(初期只有 ``spec_snapshot``),绝不是对图的变更。模型输出只携带
grounded 内容与 source 引用;所有 id 由 runtime 侧分配。
"""

from typing import List, Literal, Optional
from uuid import UUID

from . import protocol
from .decisions import UsageView
from .inputs import StrictModel


class ArtifactSection(StrictModel):
    title: str
    content: str
    source_refs: List[str] = []


class ArtifactGenerationResult(StrictModel):
    artifact_type: Literal["spec_snapshot"]
    sections: List[ArtifactSection]
    unresolved_items: List[str] = []


class AgentArtifactResponse(StrictModel):
    protocol_version: Literal[protocol.ARTIFACT_PROTOCOL_VERSION]
    run_id: UUID
    artifact: ArtifactGenerationResult
    usage: Optional[UsageView] = None


# --- 模型输出契约(LLM 必须产出的内容,严格解析) ---------------------------


class ModelArtifactOutput(StrictModel):
    """ARTIFACT_GENERATION 的模型输出:只允许 grounded 的工件内容。"""

    artifact_type: Literal["spec_snapshot"]
    sections: List[ArtifactSection]
    unresolved_items: List[str] = []
