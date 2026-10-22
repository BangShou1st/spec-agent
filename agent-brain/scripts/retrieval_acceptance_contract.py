"""Offline qualification only. No network, model, database, indexing or retrieval imports."""
import hashlib
import json
import math
import re
import sys
import platform
import importlib.metadata
from datetime import datetime
from pathlib import Path

AI_MODE = 'AI_AUDITED_SYNTHETIC_EVALUATION'
HUMAN_MODE = 'HUMAN_APPROVED_INDEPENDENT_ACCEPTANCE'
PLACEHOLDERS = {'none', 'null', 'pending', 'ai', 'ai-agent', 'unassigned', ''}
VERSIONS = {'v1', 'v2', 'v2-revised', 'v2.1', 'v2.2', 'v2.3'}


def sha_bytes(raw):
    return hashlib.sha256(raw).hexdigest()


def sha_file(path):
    return sha_bytes(Path(path).read_bytes())


def strict_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON key: ' + key)
            result[key] = value
        return result
    value = json.loads(raw, object_pairs_hook=unique,
                       parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Non-finite JSON')))
    if not isinstance(value, dict):
        raise ValueError('Expected JSON object')
    return value


def ratio(value):
    return type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 1


def instant(value):
    if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d\d-\d\dT(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)', value):
        return False
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00')).tzinfo is not None
    except ValueError:
        return False


def validate_contract(candidate, raw, freeze, root, *, ai=False, queries_count=68, sources_count=28):
    failures = []
    def check(condition, reason):
        if not condition:
            failures.append(reason)
    version = candidate.get('version')
    check(version in VERSIONS and candidate.get('datasetVersion') == version
          and freeze.get('datasetVersion') == version and freeze.get('freezeVersion') == version,
          'Missing, unsupported or mismatched candidate/freeze version')
    role = AI_MODE if ai else 'INDEPENDENT_ACCEPTANCE_CANDIDATE'
    check(candidate.get('datasetRole') == role and freeze.get('datasetRole') == role, 'Dataset role mismatch')
    check(candidate.get('executionStatus') == 'NOT_RUN', 'Prior execution history detected')
    check(candidate.get('tuningUse') == 'NEVER_USED', 'Contaminated by tuning use')
    check(sha_bytes(raw) == freeze.get('datasetSha256'), 'Dataset SHA-256 mismatch')
    check(strict_json(raw) == candidate, 'Parsed dataset differs from raw snapshot')
    if ai:
        check(candidate.get('humanReviewStatus') == 'PENDING'
              and 'reviewedBy' in candidate and candidate['reviewedBy'] is None
              and 'reviewedAt' in candidate and candidate['reviewedAt'] is None, 'AI must preserve pending human approval')
    else:
        check(candidate.get('humanReviewStatus') == 'APPROVED', 'Top-level humanReviewStatus is not APPROVED')
        reviewer = candidate.get('reviewedBy')
        check(isinstance(reviewer, str) and reviewer.strip().lower() not in PLACEHOLDERS, 'Genuine human reviewer identity missing')
        check(instant(candidate.get('reviewedAt')), 'Human review timestamp is not a valid ISO-8601 instant')
    sources, queries = candidate.get('sources'), candidate.get('queries')
    check(isinstance(sources, list) and len(sources) == sources_count, 'Source count mismatch')
    check(isinstance(queries, list) and len(queries) == queries_count, 'Query count mismatch')
    if not isinstance(sources, list) or not isinstance(queries, list):
        return failures
    aliases = {s.get('alias'): s for s in sources}
    check(len(aliases) == len(sources) and all(isinstance(a, str) and a.strip() for a in aliases), 'Duplicate/blank source alias')
    ids = [q.get('id') for q in queries]
    check(len(set(ids)) == len(ids) and all(isinstance(i, str) and i.strip() for i in ids), 'Duplicate/blank query id')
    if queries_count == 68:
        check(set(ids) == {f'B{i:03d}' for i in range(1, 69)}, 'Query identities must be B001-B068')
    for query in queries:
        check(query.get('labelReview') == ('PENDING_HUMAN_REVIEW' if ai else 'HUMAN_REVIEWED'), 'Individual query reviews pending: ' + str(query.get('id')))
        scopes = query.get('scopes', [])
        check(isinstance(scopes, list) and bool(scopes) and set(scopes) <= {'PROJECT', 'RESOURCE'}, 'Invalid scopes')
        expected, forbidden = query.get('expectedAliases'), query.get('forbiddenAliases')
        if not isinstance(expected, list) or not isinstance(forbidden, list):
            failures.append('Missing alias lists')
            continue
        check(len(set(expected)) == len(expected) and len(set(forbidden)) == len(forbidden), 'Duplicate aliases')
        check(set(expected + forbidden) <= aliases.keys() and not set(expected) & set(forbidden), 'Unknown/overlapping aliases')
        for alias in expected:
            kind = aliases.get(alias, {}).get('kind')
            check(kind not in {'STALE_NODE', 'PRIVATE_NODE'}, 'Forbidden expected source')
            check(('RESOURCE' if kind == 'RESOURCE' else 'PROJECT') in scopes, 'Expected alias outside query scope')
        if query.get('category') == 'MULTI_SOURCE':
            check(len(expected) >= 2, 'Missing multi-source labels')
        if queries_count == 68:
            check(query.get('category') in {'PROJECT_EXPRESSION', 'EXACT_IDENTIFIER', 'RESOURCE_CONTENT', 'MULTI_SOURCE',
                  'NEAR_NO_ANSWER', 'NO_ANSWER', 'STALE_VERSION', 'PERMISSION_ISOLATION'}, 'Unknown query category')
            positive = query.get('category') in {'PROJECT_EXPRESSION', 'EXACT_IDENTIFIER', 'RESOURCE_CONTENT', 'MULTI_SOURCE'}
            check(positive != (len(expected) == 0), 'Missing/inconsistent positive labels')
            check({a for a, s in aliases.items() if s['kind'] in {'STALE_NODE', 'PRIVATE_NODE'}} <= set(forbidden), 'Incomplete forbidden sources')
    for field, expected in {'rrfK': 60, 'maxItems': 8, 'maxChars': 8000}.items():
        check(type(freeze.get(field)) is int and freeze[field] == expected, field + ' mismatch')
    check(type(freeze.get('maxVectorDistance')) in (int, float) and freeze['maxVectorDistance'] == .5, 'maxVectorDistance mismatch')
    check(freeze.get('modelTag') == 'qwen3-embedding:0.6b', 'modelTag mismatch')
    check(freeze.get('profileId') == '26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047', 'profileId mismatch')
    check(freeze.get('requirementsLockSha256') == sha_file(root / 'agent-brain/requirements.lock'), 'requirements.lock drift')
    hashes = freeze.get('codeHashes')
    check(isinstance(hashes, dict) and bool(hashes), 'codeHashes missing or empty')
    for name, digest in (hashes or {}).items():
        path = (root / name).resolve()
        check(path.is_relative_to(root.resolve()) and path.is_file() and sha_file(path) == digest, 'Code hash drift: ' + name)
    status = freeze.get('thresholdStatus')
    check(status == 'AI_BASELINE_FROZEN' if ai else isinstance(status, str) and status.strip().upper() in {'APPROVED', 'APPROVED_BEFORE_FIRST_RUN'},
          'Quality acceptance thresholds unapproved')
    thresholds = freeze.get('proposedThresholds')
    if not isinstance(thresholds, dict):
        thresholds = freeze.get('approvedThresholds', {})
    for metric in ('macroSourceRecallAt8', 'firstRelevantMrr', 'multiSourceAllOfCoverage'):
        check(ratio(thresholds.get(metric)), 'Invalid/missing threshold: ' + metric)
    if ai:
        check(thresholds.get('sourceIdentityValidity') == 1.0 and ratio(thresholds.get('sourceIdentityValidity')), 'Source identity validity must be 1.0')
        check(freeze.get('noAnswerPolicy') == 'DIAGNOSTIC_RETRIEVAL_NOISE', 'AI no-answer policy missing')
    else:
        check(ratio(thresholds.get('pureNoAnswerZeroHitCorrectness')), 'Invalid/missing threshold: pureNoAnswerZeroHitCorrectness')
    for metric in ('staleSourceLeakCount', 'permissionLeakCount'):
        check(type(thresholds.get(metric)) is int and thresholds[metric] == 0, 'Safety threshold must be zero: ' + metric)
    return failures


def validate_ai_audit(candidate, freeze, audit, audit_raw, root):
    failures = []
    def check(condition, reason):
        if not condition:
            failures.append(reason)
    check(audit.get('schemaVersion') == 'ai-review-decisions.v1' and audit.get('reviewStatus') == 'AI_AUDITED'
          and audit.get('evaluationMode') == AI_MODE, 'Audit version/status/mode')
    check(audit.get('humanApproval') is False, 'AI must not claim human approval')
    check(freeze.get('evaluationFamilyId') == 'rag-d3-v2-68', 'Evaluation family binding')
    check(type(freeze.get('dimensions')) is int and freeze['dimensions'] == 1024
          and freeze.get('modelDigest') == 'ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d', 'Fixed model dimension/digest binding')
    check(audit.get('datasetSha256') == freeze.get('datasetSha256'), 'Audit dataset binding')
    check(sha_bytes(audit_raw) == freeze.get('aiReviewSha256'), 'Audit hash')
    check(audit.get('metricContract') == freeze.get('metricContract') and freeze.get('proposedThresholds') == audit.get('metricContract', {}).get('hardThresholds'), 'Metric contract binding')
    check(audit.get('tuningUse') == 'NEVER_USED' and type(audit.get('executedQueries')) is int and audit['executedQueries'] == 0, 'Audit history')
    parent = sha_file(root / 'contracts/retrieval/evaluation/acceptance-candidate.v2.2.json')
    check(candidate.get('parentDatasetSha256') == audit.get('parentDatasetSha256') == parent, 'Parent binding')
    parent_freeze = strict_json((root / 'contracts/retrieval/evaluation/acceptance-freeze.v2.2.json').read_bytes())
    check(freeze.get('parentFreezeSha256') == sha_file(root / 'contracts/retrieval/evaluation/acceptance-freeze.v2.2.json'), 'Parent freeze hash')
    required = set(parent_freeze['codeHashes']) | {'backend/src/test/java/com/specagent/retrieval/eval/RetrievalEvaluationSnapshot.java', 'agent-brain/scripts/retrieval_acceptance_contract.py'}
    check(required <= freeze.get('codeHashes', {}).keys(), 'Incomplete implementation freeze')
    check(freeze.get('noAnswerPolicy') == audit.get('metricContract', {}).get('noAnswerPolicy'), 'No-answer contract binding')
    for decisions_key, entries_key, id_key, entry_key in [('queryDecisions', 'queries', 'queryId', 'id'), ('sourceDecisions', 'sources', 'sourceAlias', 'alias')]:
        entries = {entry[entry_key]: entry for entry in candidate[entries_key]}
        decisions = audit.get(decisions_key, [])
        check(len(decisions) == len(entries), 'Complete audit required')
        seen = set()
        for decision in decisions:
            key = decision.get(id_key)
            check(key in entries and key not in seen, 'Unknown/duplicate audit entry')
            seen.add(key)
            check(decision.get('conclusion') in {'AI_ACCEPTED', 'AI_CORRECTED'}, 'Unresolved/unreviewed audit entry')
            check(decision.get('reviewerType') == 'AI' and decision.get('reviewModel') == 'GPT-6.1 Sol', 'AI reviewer identity')
            check(decision.get('after') == entries.get(key), 'Audit current labels/source mismatch')
            check(decision.get('datasetSha256') == freeze['datasetSha256'], 'Decision dataset binding')
            check(type(decision.get('actuallyModified')) is bool and decision['actuallyModified'] == (decision.get('before') != decision.get('after')), 'Decision diff consistency')
            check(bool(decision.get('evidence')), 'Audit evidence missing')
            for evidence in decision.get('evidence', []):
                path = (root / evidence.get('file', '')).resolve()
                check(path.is_relative_to(root.resolve()) and path.is_file() and sha_file(path) == evidence.get('sha256'), 'Evidence drift/path')
                check(type(evidence.get('line')) is int and 0 < evidence['line'] <= len(path.read_text(encoding='utf-8').splitlines()), 'Evidence line')
    return failures


def preflight(root):
    data = root / 'contracts/retrieval/evaluation'
    raw = (data / 'acceptance-candidate.v2.3.json').read_bytes()
    frozen_raw = (data / 'acceptance-freeze.v2.3.json').read_bytes()
    audit_raw = (data / 'ai-review-decisions.v1.json').read_bytes()
    candidate, freeze, audit = map(strict_json, (raw, frozen_raw, audit_raw))
    failures = validate_contract(candidate, raw, freeze, root, ai=True)
    failures += validate_ai_audit(candidate, freeze, audit, audit_raw, root)
    inventory_path = data / 'runtime-inventory.v1.json'
    if sha_file(inventory_path) != freeze.get('runtimeInventorySha256'):
        failures.append('Runtime inventory hash')
    if runtime_inventory(root) != strict_json(inventory_path.read_bytes()):
        failures.append('Actual interpreter/dependencies differ from frozen inventory')
    claims = root / 'data/retrieval-eval-claims'
    # Any legacy claim is evidence of exposure; changing filenames does not reset it.
    existing = list(claims.glob('*.claim.json')) if claims.exists() else []
    if existing:
        failures.append('Existing execution claims require history reconciliation')
    return {'status': 'AI_AUDIT_BLOCKED' if failures else 'AI_AUDIT_READY_FOR_FIRST_RUN_AUTHORIZATION',
            'failures': failures, 'datasetSha256': sha_bytes(raw), 'freezeSha256': sha_bytes(frozen_raw),
            'auditSha256': sha_bytes(audit_raw), 'queriesReviewed': len(audit['queryDecisions']),
            'sourcesReviewed': len(audit['sourceDecisions']), 'queriesExecutedThisRun': 0,
            'localExecutionClaims': len(existing), 'humanApproval': 'PENDING',
            'historyLimit': 'File/ledger evidence only; cannot prove absence of undisclosed off-ledger execution'}


def runtime_inventory(root):
    pins = dict(line.split('==', 1) for line in (root / 'agent-brain/requirements.lock').read_text().splitlines() if '==' in line)
    installed = {name: importlib.metadata.version(name) for name in pins}
    if installed != pins:
        raise ValueError('Actual packages differ from requirements.lock')
    return {'schemaVersion': 'rag-runtime-inventory.v1', 'pythonExecutable': str(Path(sys.executable).resolve()),
            'pythonVersion': platform.python_version(), 'requirementsLockSha256': sha_file(root / 'agent-brain/requirements.lock'),
            'installedPinnedPackages': installed, 'brainPackageVersion': importlib.metadata.version('spec-agent-brain')}


if __name__ == '__main__':
    root = Path(__file__).resolve().parents[2]
    if sys.argv[1:] == ['--runtime-inventory']:
        print(json.dumps(runtime_inventory(root), ensure_ascii=False, sort_keys=True))
        raise SystemExit(0)
    result = preflight(root)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(1 if result['failures'] else 0)
