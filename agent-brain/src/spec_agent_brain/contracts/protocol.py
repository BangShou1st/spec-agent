"""Frozen protocol constants of the V2 cross-language agent boundary.

Mirrors ``com.specagent.agent.contract.AgentProtocol`` on the Java side
and the authoritative ``contracts/README.md``.
"""

INPUT_PROTOCOL_VERSION = "agent-input.v2"
DECISION_PROTOCOL_VERSION = "agent-decision.v2"
INFERENCE_PROTOCOL_VERSION = "model-inference.v1"

INTERNAL_TOKEN_HEADER = "X-Spec-Agent-Internal-Token"

CALL_TYPES = ("STATE_UPDATE", "DECISION")

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
