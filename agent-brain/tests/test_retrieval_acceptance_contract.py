"""Offline adversarial preflight tests. Never retrieve the candidate queries."""
import copy
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'agent-brain'))
from scripts.retrieval_acceptance_contract import (
    validate_contract, validate_ai_audit, strict_json, sha_bytes, sha_file, preflight,
)

DATA = ROOT / 'contracts/retrieval/evaluation'
CASES = json.loads((DATA / 'gate-parity-cases.v1.json').read_text())


@pytest.mark.parametrize('case', CASES, ids=lambda c: c['id'])
def test_shared_java_python_gate_cases(case):
    candidate = strict_json((DATA / 'acceptance-candidate.v2.2.json').read_bytes())
    candidate.update(humanReviewStatus='APPROVED', reviewedBy='Synthetic Test Reviewer', reviewedAt='2026-10-22T12:00:00Z')
    candidate['sources'] = candidate['sources'][:2]
    candidate['queries'] = candidate['queries'][:2]
    for q, source in zip(candidate['queries'], candidate['sources']):
        q.update(labelReview='HUMAN_REVIEWED', expectedAliases=[source['alias']], forbiddenAliases=[])
    freeze = strict_json((DATA / 'acceptance-freeze.v2.2.json').read_bytes())
    freeze['thresholdStatus'] = 'APPROVED_BEFORE_FIRST_RUN'
    freeze['codeHashes'] = {name: sha_file(ROOT / name) for name in freeze['codeHashes']}
    if case['path']:
        parts = case['path'].split('/')
        target = candidate if parts[0] == 'dataset' else freeze
        for part in parts[1:-1]:
            target = target[part]
        if case['value'] is None:
            target.pop(parts[-1], None)
        else:
            target[parts[-1]] = case['value']
    raw = json.dumps(candidate).encode()
    freeze['datasetSha256'] = sha_bytes(raw)
    failures = validate_contract(candidate, raw, freeze, ROOT, queries_count=2, sources_count=2)
    assert (not failures) == case['valid'], failures


@pytest.mark.parametrize('raw', ['{', '{} {}', '{"x":1,"x":2}', '{"x":NaN}', '[]'])
def test_invalid_json_fails_closed(raw):
    with pytest.raises((ValueError, json.JSONDecodeError)):
        strict_json(raw)


def assets():
    return [strict_json((DATA / name).read_bytes()) for name in (
        'acceptance-candidate.v2.3.json', 'acceptance-freeze.v2.3.json', 'ai-review-decisions.v1.json')]


def test_archived_ai_review_and_human_gate_remain_separate():
    # D3 is historical: its code/runtime identity is not a requirement that all
    # later development stays byte-identical. Validate the immutable review,
    # not readiness to execute D3 again from today's checkout.
    candidate, freeze, audit = assets()
    assert len(candidate['queries']) == 68 and len(candidate['sources']) == 28
    assert sha_file(DATA / 'acceptance-candidate.v2.3.json') == freeze['datasetSha256']
    assert sha_file(DATA / 'ai-review-decisions.v1.json') == freeze['aiReviewSha256']
    import jsonschema
    jsonschema.Draft202012Validator(strict_json((DATA / 'ai-review-decisions.v1.schema.json').read_bytes()),
        format_checker=jsonschema.FormatChecker()).validate(audit)
    raw = (DATA / 'acceptance-candidate.v2.3.json').read_bytes()
    failures = validate_contract(candidate, raw, freeze, ROOT)
    assert any('humanReviewStatus' in message for message in failures)
    assert candidate['reviewedBy'] is None and candidate['reviewedAt'] is None
    assert all(q['labelReview'] == 'PENDING_HUMAN_REVIEW' for q in candidate['queries'])


def test_current_code_identity_really_fails_closed_on_illegal_drift(tmp_path):
    # A fresh, temporary UNIT fixture, never an update of the archived freeze.
    candidate, freeze, _ = assets()
    candidate['sources'] = candidate['sources'][:2]
    candidate['queries'] = candidate['queries'][:2]
    for query, source in zip(candidate['queries'], candidate['sources']):
        query.update(expectedAliases=[source['alias']], forbiddenAliases=[], backgroundAliases=[], category='PROJECT_EXPRESSION')
    (tmp_path / 'agent-brain').mkdir()
    (tmp_path / 'agent-brain/requirements.lock').write_bytes((ROOT / 'agent-brain/requirements.lock').read_bytes())
    code = tmp_path / 'unit-source.txt'
    code.write_text('reviewed-current-code', encoding='utf-8')
    freeze['codeHashes'] = {'unit-source.txt': sha_file(code)}
    raw = json.dumps(candidate).encode()
    freeze['datasetSha256'] = sha_bytes(raw)
    assert validate_contract(candidate, raw, freeze, tmp_path, ai=True, queries_count=2, sources_count=2) == []
    code.write_text('unreviewed-drift', encoding='utf-8')
    assert 'Code hash drift: unit-source.txt' in validate_contract(
        candidate, raw, freeze, tmp_path, ai=True, queries_count=2, sources_count=2)


@pytest.mark.parametrize('mutation', ['dataset_hash', 'unresolved', 'missing_query', 'duplicate_query', 'changed_label', 'source_evidence', 'missing_implementation', 'metric_drift'])
def test_tampered_audit_never_qualifies(mutation):
    candidate, freeze, audit = assets()
    if mutation == 'dataset_hash': audit['datasetSha256'] = '0' * 64
    elif mutation == 'unresolved': audit['queryDecisions'][0]['conclusion'] = 'AI_UNRESOLVED'
    elif mutation == 'missing_query': audit['queryDecisions'].pop()
    elif mutation == 'duplicate_query': audit['queryDecisions'][1] = copy.deepcopy(audit['queryDecisions'][0])
    elif mutation == 'changed_label': audit['queryDecisions'][0]['after']['expectedAliases'] = []
    elif mutation == 'source_evidence': audit['sourceDecisions'][0]['evidence'][0]['sha256'] = '0' * 64
    elif mutation == 'missing_implementation': freeze['codeHashes'].pop('backend/src/test/java/com/specagent/retrieval/eval/RetrievalExecutionClaimManager.java')
    elif mutation == 'metric_drift': audit['metricContract']['hardThresholds']['firstRelevantMrr'] = .01
    raw = json.dumps(audit, ensure_ascii=False).encode()
    freeze['aiReviewSha256'] = sha_bytes(raw)  # A new outer hash cannot disguise invalid review contents.
    assert validate_ai_audit(candidate, freeze, audit, raw, ROOT)


def test_decision_diffs_and_complete_scope_alias_contract():
    candidate, freeze, audit = assets()
    original = strict_json((DATA / 'acceptance-candidate.v2.2.json').read_bytes())
    for decisions, key, entries, id_key in [
        (audit['queryDecisions'], 'queryId', original['queries'], 'id'),
        (audit['sourceDecisions'], 'sourceAlias', original['sources'], 'alias')]:
        before = {e[id_key]: e for e in entries}
        for decision in decisions:
            assert decision['before'] == before[decision[key]]
            assert decision['actuallyModified'] == (decision['before'] != decision['after'])
            assert bool(decision['changes']) == decision['actuallyModified']
            assert decision['datasetSha256'] == freeze['datasetSha256']
    aliases = {s['alias']: s for s in candidate['sources']}
    for q in candidate['queries']:
        background = set(q.get('backgroundAliases', []))
        assert background <= aliases.keys()
        assert not background & set(q['expectedAliases'] + q['forbiddenAliases'])
        assert all(('RESOURCE' if aliases[a]['kind'] == 'RESOURCE' else 'PROJECT') in q['scopes'] for a in background)
    assert sum(bool(q['expectedAliases']) for q in candidate['queries']) == 49
    assert sum(q['category'] == 'MULTI_SOURCE' for q in candidate['queries']) == 8


def test_unknown_negative_category_cannot_disappear_into_diagnostics():
    candidate, freeze, _ = assets()
    candidate['queries'][-1]['category'] = 'UNKNOWN'
    raw = json.dumps(candidate).encode()
    freeze['datasetSha256'] = sha_bytes(raw)
    assert 'Unknown query category' in validate_contract(candidate, raw, freeze, ROOT, ai=True)
