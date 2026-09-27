"""文件名:test_contracts.py

用途:与 Java 侧共享的 golden fixture 契约测试。

``contracts/fixtures`` 下的 fixture 是跨语言的唯一权威:合法的必须能解析,
``invalid`` 的必须被拒绝。
"""

import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from spec_agent_brain.contracts.decisions import (
    AgentV2ResponseEnvelope,
    AgentV3ResponseEnvelope,
    ObservationView,
    validate_v3_response_for_request,
)
from spec_agent_brain.contracts.inputs import parse_request_envelope
from spec_agent_brain.model_client.fake import (
    ARTIFACT_GENERATION_OUTPUT,
    DECISION_OUTPUT,
    STATE_UPDATE_OUTPUT,
)

FIXTURES_DIR = Path(__file__).resolve().parents[2] / "contracts" / "fixtures"


def _load(name: str) -> dict:
    return json.loads((FIXTURES_DIR / name).read_text(encoding="utf-8"))


def test_valid_request_fixture_parses():
    envelope = parse_request_envelope(_load("agent-input-valid.json"))
    assert str(envelope.run_id) == "22222222-2222-2222-2222-222222222222"
    assert envelope.snapshot.metadata.project_title == "内部工单系统探索"
    assert len(envelope.snapshot.lineage) == 2
    # Phase 4:不含 availableSkills 的旧 fixture 解析为空目录。
    assert envelope.snapshot.available_skills.skills == []
    assert envelope.snapshot.available_skills.truncated is False


def test_skill_catalog_round_trips_with_fingerprint():
    envelope = parse_request_envelope(_load("agent-input-valid.json"))
    payload = copy.deepcopy(_load("agent-input-valid.json"))
    payload["snapshot"]["availableSkills"] = {
        "skills": [
            {"skillId": "sk-1", "name": "migration-safety",
             "description": "数据库迁移安全检查"},
        ],
        "truncated": False,
        "fingerprint": "fp-1",
    }
    reparsed = parse_request_envelope(payload)
    assert [s.skill_id for s in reparsed.snapshot.available_skills.skills] == ["sk-1"]
    assert reparsed.snapshot.available_skills.fingerprint == "fp-1"
    # 未知的 skill 字段仍然 fail-closed。
    bad = copy.deepcopy(payload)
    bad["snapshot"]["availableSkills"]["skills"][0]["filesystemPath"] = "/tmp/x"
    with pytest.raises(ValidationError):
        parse_request_envelope(bad)


def test_routeless_node_query_fixture_parses_with_null_route_ids():
    # 对 Floating(无路由)Graph 节点的 NODE_QUERY 是唯一允许携带 null
    # route id 的语义流程;见 contracts/README.md 中 Stage C 的 NODE_QUERY
    # 无路由可空性说明。
    envelope = parse_request_envelope(
        _load("agent-input-routeless-node-query-valid.json"))
    assert envelope.event.kind == "NODE_QUERY"
    assert envelope.snapshot.route_id is None
    assert envelope.snapshot.route_context.route_id is None
    # 绑定路由的镜像字段在普通流程中仍为必填:基线 fixture 的 route id
    # 保持存在且可解析。
    baseline = parse_request_envelope(_load("agent-input-valid.json"))
    assert baseline.snapshot.route_id is not None
    assert baseline.snapshot.route_context.route_id is not None
    # 无路由形态绝不允许发明 ``route:`` source ref。
    for ref in envelope.snapshot.allowed_source_refs:
        assert not ref.startswith("route:")


def test_node_query_semantic_context_fixture_parses_with_bounded_one_hop():
    # Stage C 有界的 1-hop 语义上下文:relations 保留方向,relatedNodes
    # 携带真实投影后的节点 body,node:<relatedId> 是一等公民的
    # allowedSourceRef,且相关节点绝不进入 lineage。
    envelope = parse_request_envelope(
        _load("agent-input-node-query-semantic-context-valid.json"))
    assert envelope.event.kind == "NODE_QUERY"
    assert envelope.snapshot.route_id is not None
    assert envelope.snapshot.route_id == envelope.snapshot.route_context.route_id

    assert len(envelope.snapshot.relations) == 1
    relation = envelope.snapshot.relations[0]
    assert str(relation.source_node_id) == "05000000-0000-0000-0000-000000000005"
    assert str(relation.target_node_id) == "06000000-0000-0000-0000-000000000006"
    assert relation.relation_type == "SUPPORTS"

    assert len(envelope.snapshot.related_nodes) == 1
    ref = envelope.snapshot.related_nodes[0]
    assert str(ref.node_id) == "06000000-0000-0000-0000-000000000006"
    assert ref.relation_type == "SUPPORTS"
    assert ref.direction == "OUTGOING"
    # 相关节点的真实正文内容会随线上格式传输。
    assert "离线队列容量上限 2048 条" in ref.node.body.text
    assert ref.node.kind == "RESOURCE"

    # 相关节点是一个 source ref,并且不进入 lineage。
    assert "node:06000000-0000-0000-0000-000000000006" in envelope.snapshot.allowed_source_refs
    assert len(envelope.snapshot.lineage) == 1
    assert envelope.snapshot.lineage[0].node.id != ref.node_id
    assert str(envelope.snapshot.lineage[0].node.id) == "05000000-0000-0000-0000-000000000005"


def test_retrieved_context_preserves_scope_authority_and_resource_location():
    payload = copy.deepcopy(_load("agent-input-valid.json"))
    payload["snapshot"]["retrievedContext"] = [
        {
            "sourceRef": "answer:11111111-1111-1111-1111-111111111111",
            "sourceKind": "ANSWER",
            "scope": "PROJECT",
            "originRouteId": "33333333-3333-3333-3333-333333333333",
            "authority": "CONFIRMED",
            "content": "另一个路线已经确认企业 SSO。",
            "location": None,
            "provenance": {"originRouteId": "33333333-3333-3333-3333-333333333333"},
            "retrievalReason": "workspace-project-memory",
        },
        {
            "sourceRef": "resource-chunk:44444444-4444-4444-4444-444444444444:17",
            "sourceKind": "RESOURCE_CHUNK",
            "scope": "RESOURCE",
            "authority": "EXTERNAL_EVIDENCE",
            "content": "系统要求数据留存180天。",
            "location": {"resourceId": "44444444-4444-4444-4444-444444444444", "chunk": 17, "page": 23},
            "provenance": {},
        },
    ]
    envelope = parse_request_envelope(payload)
    items = envelope.snapshot.retrieved_context
    assert [item.scope for item in items] == ["PROJECT", "RESOURCE"]
    assert items[0].origin_route_id is not None
    assert items[0].authority == "CONFIRMED"
    assert items[1].location["chunk"] == 17

    bad = copy.deepcopy(payload)
    bad["snapshot"]["retrievedContext"][0]["cosine"] = 0.9
    with pytest.raises(ValidationError):
        parse_request_envelope(bad)


def test_request_with_unknown_field_is_rejected():
    payload = _load("agent-input-invalid-unknown-field.json")
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_request_with_unknown_protocol_version_is_rejected():
    payload = _load("agent-input-invalid-unknown-version.json")
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_valid_decision_response_fixture_parses():
    response = AgentV2ResponseEnvelope.model_validate(_load("decision-response-valid.json"))
    assert response.action_proposal is not None
    assert response.action_proposal.action_family == "REQUEST_USER_INPUT"


def test_valid_v3_eligibility_request_and_response_pass_strict_validation():
    request = parse_request_envelope(_load("agent-input-v3-valid.json"))
    response = AgentV3ResponseEnvelope.model_validate(
        _load("decision-response-v3-valid.json"))
    assert request.action_eligibility.version == "action-eligibility.v1"
    validate_v3_response_for_request(request, response)


def test_runtime_owned_persistence_intent_is_optional_and_typed():
    payload = copy.deepcopy(_load("agent-input-v3-valid.json"))
    payload["event"]["persistenceIntent"] = "RECORD_DECISION_NODE"

    envelope = parse_request_envelope(payload)

    assert envelope.event.persistence_intent == "RECORD_DECISION_NODE"

    payload["event"]["persistenceIntent"] = "free-text-authorization"
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


@pytest.mark.parametrize(
    "fixture_name",
    [
        "agent-input-v3-invalid-family.json",
        "agent-input-v3-invalid-unknown-field.json",
        "agent-input-v3-invalid-version.json",
    ],
)
def test_invalid_v3_request_fixtures_are_rejected(fixture_name: str):
    with pytest.raises(ValidationError):
        parse_request_envelope(_load(fixture_name))


@pytest.mark.parametrize(
    "fixture_name",
    [
        "decision-response-v3-invalid-unknown-field.json",
        "decision-response-v3-invalid-version.json",
    ],
)
def test_invalid_v3_response_schema_is_rejected(fixture_name: str):
    with pytest.raises(ValidationError):
        AgentV3ResponseEnvelope.model_validate(_load(fixture_name))


@pytest.mark.parametrize(
    "fixture_name",
    [
        "decision-response-v3-invalid-digest.json",
        "decision-response-v3-invalid-evidence-ref.json",
    ],
)
def test_invalid_v3_response_semantics_are_rejected(fixture_name: str):
    request = parse_request_envelope(_load("agent-input-v3-valid.json"))
    response = AgentV3ResponseEnvelope.model_validate(_load(fixture_name))
    with pytest.raises(ValueError):
        validate_v3_response_for_request(request, response)


def test_v2_request_cannot_carry_v3_eligibility_fields():
    payload = _load("agent-input-v3-valid.json")
    payload["protocolVersion"] = "agent-input.v2"
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


@pytest.mark.parametrize(
    "fixture_name",
    [
        "decision-response-invalid-unknown-action-family.json",
    ],
)
def test_invalid_decision_response_fixtures_are_rejected(fixture_name: str):
    with pytest.raises(ValidationError):
        AgentV2ResponseEnvelope.model_validate(_load(fixture_name))


@pytest.mark.parametrize(
    "fixture_name",
    [
        # 这两个 fixture 在结构上是合法的信封:捏造的 source ref 与过期的
        # base context 属于语义违规,由 Java 的 fail-closed 校验器拒绝
        # (brain 引擎自身也会预检),不属于 schema 违规。
        "decision-response-invalid-invented-source-ref.json",
        "decision-response-invalid-stale-base-context.json",
    ],
)
def test_semantically_invalid_fixtures_still_parse_as_envelopes(fixture_name: str):
    response = AgentV2ResponseEnvelope.model_validate(_load(fixture_name))
    assert response.action_proposal is not None


def test_state_update_response_fixture_parses():
    response = AgentV2ResponseEnvelope.model_validate(_load("state-update-response-valid.json"))
    assert response.state_update is not None
    assert response.state_update.claims[0].kind == "goal"


def test_runtime_owned_claim_id_is_rejected():
    # brain 绝不能产出 runtime 持有的标识字段;严格模型拒绝这个未知键,
    # 而不是悄悄接受。
    payload = _load("state-update-response-invalid-runtime-owned-id.json")
    with pytest.raises(ValidationError):
        AgentV2ResponseEnvelope.model_validate(payload)


def test_fake_model_constants_match_golden_fixtures():
    assert json.loads(STATE_UPDATE_OUTPUT) == _load("fake-model-state-update-output.json")
    assert json.loads(DECISION_OUTPUT) == _load("fake-model-decision-output.json")
    assert json.loads(ARTIFACT_GENERATION_OUTPUT) == _load(
        "fake-model-artifact-output.json")


def test_observation_entries_shaped_like_objects_are_coerced_to_strings():
    # 上下文变长后,模型有时会模仿它看到过的 observation 条目,输出对象
    # 而不是字符串。reflection 条目不属于信任边界,所以契约对它们做
    # 确定性的字符串化,而不是让整个 DECISION 周期以 brain_unavailable
    # 的形式失败。
    view = ObservationView.model_validate({
        "known": [
            {"sourceRef": "answer:28f1", "excerpt": "用户要做技术设计评审"},
            {"source": "snapshot.availableSkills（truncated: false）"},
            "plain string stays untouched",
        ],
        "unknowns": [{"question": "用户想要哪种承载形式？"}],
        "conflicts": [],
        "risks": [],
    })
    assert view.known[0] == "answer:28f1: 用户要做技术设计评审"
    assert view.known[1] == "snapshot.availableSkills（truncated: false）"
    assert view.known[2] == "plain string stays untouched"
    assert view.unknowns[0] == "用户想要哪种承载形式？"


def test_observation_entry_object_without_text_content_is_rejected():
    with pytest.raises(ValidationError):
        ObservationView.model_validate({"known": [{"unrelated": 1}]})


# ---------------------------------------------------------------------------
# 无路由 NODE_QUERY 契约:只有 NODE_QUERY 可以使用 null route id,且
# snapshot.routeId / snapshot.routeContext.routeId 必须始终一致。这些测试
# 由现有合法 fixture 程序化派生;golden fixture 本身保持不动。
# ---------------------------------------------------------------------------

_ANSWER_BOUND = _load("agent-input-valid.json")
_ROUTELESS = _load("agent-input-routeless-node-query-valid.json")


def _swap_event_kind(payload: dict, kind: str) -> None:
    payload["event"] = dict(payload["event"])
    payload["event"]["kind"] = kind


def _set_route_ids(payload: dict, snapshot_route, context_route) -> None:
    payload["snapshot"] = copy.deepcopy(payload["snapshot"])
    payload["snapshot"]["routeId"] = snapshot_route
    payload["snapshot"]["routeContext"] = dict(payload["snapshot"]["routeContext"])
    payload["snapshot"]["routeContext"]["routeId"] = context_route


def test_answer_submitted_with_null_route_id_is_rejected():
    payload = copy.deepcopy(_ANSWER_BOUND)
    _set_route_ids(payload, None, None)
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_continue_with_null_route_id_is_rejected():
    payload = copy.deepcopy(_ANSWER_BOUND)
    _swap_event_kind(payload, "CONTINUE")
    _set_route_ids(payload, None, None)
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_initial_with_null_route_id_is_rejected():
    payload = copy.deepcopy(_ANSWER_BOUND)
    _swap_event_kind(payload, "INITIAL")
    _set_route_ids(payload, None, None)
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_node_query_with_only_snapshot_route_id_null_is_rejected():
    # 混合状态:snapshot 为 null,routeContext 有 UUID。
    payload = copy.deepcopy(_ROUTELESS)
    _set_route_ids(
        payload,
        None,
        "99999999-9999-9999-9999-999999999999",
    )
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_node_query_with_only_route_context_route_id_null_is_rejected():
    # 混合状态:snapshot 有 UUID,routeContext 为 null。
    payload = copy.deepcopy(_ROUTELESS)
    _set_route_ids(
        payload,
        "99999999-9999-9999-9999-999999999999",
        None,
    )
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_route_bound_node_query_with_mismatched_route_ids_is_rejected():
    # NODE_QUERY 两个 route id 都非空但不相等,必须被拒绝。
    payload = copy.deepcopy(_ROUTELESS)
    _set_route_ids(
        payload,
        "99999999-9999-9999-9999-999999999999",
        "88888888-8888-8888-8888-888888888888",
    )
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)
