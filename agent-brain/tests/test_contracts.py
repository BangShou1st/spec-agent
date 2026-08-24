"""Golden-fixture contract tests shared with the Java side.

The fixtures under ``contracts/fixtures`` are the single cross-language
authority: valid ones must parse, ``invalid`` ones must be rejected.
"""

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
