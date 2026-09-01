"""Golden-fixture contract tests shared with the Java side.

The fixtures under ``contracts/fixtures`` are the single cross-language
authority: valid ones must parse, ``invalid`` ones must be rejected.
"""

import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from spec_agent_brain.contracts.decisions import AgentV2ResponseEnvelope
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


def test_routeless_node_query_fixture_parses_with_null_route_ids():
    # A NODE_QUERY against a Floating (routeless) Graph node is the one
    # semantic flow allowed to carry null route ids; see Stage C NODE_QUERY
    # routeless nullability in contracts/README.md.
    envelope = parse_request_envelope(
        _load("agent-input-routeless-node-query-valid.json"))
    assert envelope.event.kind == "NODE_QUERY"
    assert envelope.snapshot.route_id is None
    assert envelope.snapshot.route_context.route_id is None
    # The route-bound mirror must still be required for normal flows: the
    # baseline fixture's route ids stay present and parseable.
    baseline = parse_request_envelope(_load("agent-input-valid.json"))
    assert baseline.snapshot.route_id is not None
    assert baseline.snapshot.route_context.route_id is not None
    # Routeless shape must never invent a ``route:`` source ref.
    for ref in envelope.snapshot.allowed_source_refs:
        assert not ref.startswith("route:")


def test_node_query_semantic_context_fixture_parses_with_bounded_one_hop():
    # Stage C bounded 1-hop semantic context: relations preserve direction,
    # relatedNodes carry the actual projected node body, node:<relatedId> is a
    # first-class allowedSourceRef, and the related node never enters lineage.
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
    # The related node's real body content travels on the wire.
    assert "离线队列容量上限 2048 条" in ref.node.body.text
    assert ref.node.kind == "RESOURCE"

    # Related node is a source ref and stays out of the lineage.
    assert "node:06000000-0000-0000-0000-000000000006" in envelope.snapshot.allowed_source_refs
    assert len(envelope.snapshot.lineage) == 1
    assert envelope.snapshot.lineage[0].node.id != ref.node_id
    assert str(envelope.snapshot.lineage[0].node.id) == "05000000-0000-0000-0000-000000000005"


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
        # These two are structurally valid envelopes: the invented source ref
        # and stale base context are semantic violations rejected by the Java
        # fail-closed validator (and pre-checked by the brain engine itself),
        # not schema violations.
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
    # The brain must never emit runtime-owned identity fields; the strict
    # model rejects the unknown key instead of silently accepting it.
    payload = _load("state-update-response-invalid-runtime-owned-id.json")
    with pytest.raises(ValidationError):
        AgentV2ResponseEnvelope.model_validate(payload)


def test_fake_model_constants_match_golden_fixtures():
    assert json.loads(STATE_UPDATE_OUTPUT) == _load("fake-model-state-update-output.json")
    assert json.loads(DECISION_OUTPUT) == _load("fake-model-decision-output.json")
    assert json.loads(ARTIFACT_GENERATION_OUTPUT) == _load(
        "fake-model-artifact-output.json")


# ---------------------------------------------------------------------------
# Routeless NODE_QUERY contract: only NODE_QUERY may use null route ids, and
# snapshot.routeId / snapshot.routeContext.routeId must always agree. These
# tests are derived programmatically from the existing valid fixtures; the
# golden fixtures themselves stay untouched.
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
    # Mixed state: snapshot null, routeContext has a UUID.
    payload = copy.deepcopy(_ROUTELESS)
    _set_route_ids(
        payload,
        None,
        "99999999-9999-9999-9999-999999999999",
    )
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_node_query_with_only_route_context_route_id_null_is_rejected():
    # Mixed state: snapshot has a UUID, routeContext null.
    payload = copy.deepcopy(_ROUTELESS)
    _set_route_ids(
        payload,
        "99999999-9999-9999-9999-999999999999",
        None,
    )
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)


def test_route_bound_node_query_with_mismatched_route_ids_is_rejected():
    # NODE_QUERY with two non-null but unequal route ids must be rejected.
    payload = copy.deepcopy(_ROUTELESS)
    _set_route_ids(
        payload,
        "99999999-9999-9999-9999-999999999999",
        "88888888-8888-8888-8888-888888888888",
    )
    with pytest.raises(ValidationError):
        parse_request_envelope(payload)
