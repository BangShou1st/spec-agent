"""文件名:protocol.py

用途:V2 跨语言 agent 边界的冻结协议常量。

与 Java 侧的 ``com.specagent.agent.protocol.AgentProtocol`` 以及权威文档
``contracts/README.md`` 保持一致。
"""

INPUT_PROTOCOL_VERSION = "agent-input.v2"
DECISION_PROTOCOL_VERSION = "agent-decision.v2"
INPUT_PROTOCOL_VERSION_V3 = "agent-input.v3"
DECISION_PROTOCOL_VERSION_V3 = "agent-decision.v3"
ACTION_ELIGIBILITY_VERSION = "action-eligibility.v1"
ARTIFACT_PROTOCOL_VERSION = "agent-artifact.v1"
INFERENCE_PROTOCOL_VERSION = "model-inference.v1"
RANKING_PROTOCOL_VERSION = "agent-ranking.v1"
RANKING_WEIGHTS_VERSION = "semantic-ranking-weights.v1"

INTERNAL_TOKEN_HEADER = "X-Spec-Agent-Internal-Token"

CALL_TYPES = ("STATE_UPDATE", "DECISION", "ARTIFACT_GENERATION")

EVENT_KINDS = ("INITIAL", "CONTINUE", "ANSWER_SUBMITTED", "NODE_QUERY")

NODE_KINDS = ("KNOWLEDGE", "INTERACTION", "RESOURCE", "ARTIFACT")

ACTION_FAMILIES = (
    "CREATE_NODE",
    "UPDATE_NODE",
    "CONNECT_NODE",
    "CREATE_ROUTE",
    "REQUEST_USER_INPUT",
    "RESPOND_TO_USER",
    "INVOKE_CAPABILITY",
    "GENERATE_ARTIFACT",
    "WAIT",
)

RANKING_PRIORITY_CLASSES = (
    "BLOCKING",
    "REQUIRED_EXTERNAL_STEP",
    "DIRECT_COMPLETION",
    "OPTIONAL_PROGRESS",
    "DURABLE_MATERIALIZATION",
)

RANKING_REASON_CODES = (
    "MISSING_USER_INFORMATION",
    "UNRESOLVED_USER_CHOICE",
    "EXTERNAL_INFORMATION_REQUIRED",
    "GROUNDED_ARGUMENTS_AVAILABLE",
    "GOAL_SATISFIED",
    "MATERIAL_NOVELTY",
    "NOT_NEEDED",
)

CLAIM_KINDS = (
    "goal",
    "stakeholder",
    "scope",
    "constraint",
    "success_criterion",
    "output_expectation",
    "risk",
    "assumption",
    "open_question",
    "conflict",
    "other",
)

CLAIM_STATUSES = ("confirmed", "assumed", "unresolved", "rejected")
