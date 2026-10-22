"""Static precheck only: never runs queries, tunes config, or approves human labels.
ReadOnly audit tool: will never overwrite human review markdown.
Supports Asset A (historical v1 regression candidate), Asset B (v2 candidate), and Asset C (v2-revised candidate).
"""
import argparse
import hashlib
import json
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'contracts/retrieval/evaluation'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def audit_single_asset(freeze_name, candidate_name, review_md_name, development_data, other_candidate_data=None):
    freeze = json.loads((DATA / freeze_name).read_text(encoding='utf-8'))
    candidate = json.loads((DATA / candidate_name).read_text(encoding='utf-8'))

    # 1. Dataset role and execution status
    expected_role = freeze.get('datasetRole', 'INDEPENDENT_ACCEPTANCE_CANDIDATE')
    assert candidate['datasetRole'] == expected_role, f"Role mismatch: {candidate['datasetRole']} vs {expected_role}"
    assert candidate['executionStatus'] == 'NOT_RUN' and candidate['tuningUse'] == 'NEVER_USED'
    assert len(candidate['queries']) == 68, f"Expected 68 candidate queries, got {len(candidate['queries'])}"
    assert len(candidate['sources']) == 28, f"Expected 28 sources, got {len(candidate['sources'])}"

    # 2. Frozen dataset hash assertion
    candidate_actual_sha = sha(DATA / freeze['datasetFile'])
    assert freeze['datasetSha256'] == candidate_actual_sha, f"Dataset hash mismatch in {freeze_name}!"

    # 3. Parameters assertion
    assert (freeze['maxVectorDistance'], freeze['rrfK'], freeze['maxItems'], freeze['maxChars']) == (0.50, 60, 8, 8000)
    assert freeze['modelTag'] == 'qwen3-embedding:0.6b'
    assert freeze['profileId'] == '26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047'

    # 4. Hash drift audit (code and requirements)
    drifted_files = []
    frozen_req_sha = freeze.get('requirementsLockSha256')
    if not frozen_req_sha or not str(frozen_req_sha).strip():
        drifted_files.append({
            'file': 'agent-brain/requirements.lock',
            'frozenHash': 'MISSING',
            'actualHash': 'N/A',
            'reason': 'requirementsLockSha256 missing or empty in freeze'
        })
    else:
        actual_req_sha = sha(ROOT / 'agent-brain/requirements.lock')
        if frozen_req_sha != actual_req_sha:
            drifted_files.append({
                'file': 'agent-brain/requirements.lock',
                'frozenHash': frozen_req_sha,
                'actualHash': actual_req_sha,
                'reason': 'Dependency drift in requirements.lock'
            })

    code_hashes = freeze.get('codeHashes')
    if not code_hashes or not isinstance(code_hashes, dict):
        drifted_files.append({
            'file': 'codeHashes',
            'frozenHash': 'MISSING',
            'actualHash': 'N/A',
            'reason': 'codeHashes missing or empty in freeze'
        })
    else:
        for rel_path, expected_hash in code_hashes.items():
            code_file = ROOT / rel_path
            if not code_file.exists():
                drifted_files.append({
                    'file': rel_path,
                    'frozenHash': expected_hash,
                    'actualHash': 'MISSING',
                    'reason': 'File missing from workspace'
                })
                continue
            actual_code_sha = sha(code_file)
            if expected_hash != actual_code_sha:
                drifted_files.append({
                    'file': rel_path,
                    'frozenHash': expected_hash,
                    'actualHash': actual_code_sha,
                    'reason': 'Implementation update or drift'
                })

    # Model and profile identity validation
    if not freeze.get('modelTag') or not str(freeze['modelTag']).strip():
        drifted_files.append({
            'file': 'modelTag',
            'frozenHash': 'MISSING',
            'actualHash': 'N/A',
            'reason': 'modelTag missing in freeze'
        })
    if not freeze.get('profileId') or not str(freeze['profileId']).strip():
        drifted_files.append({
            'file': 'profileId',
            'frozenHash': 'MISSING',
            'actualHash': 'N/A',
            'reason': 'profileId missing in freeze'
        })

    # 5. Queries and sources integrity
    assert len({s['alias'] for s in candidate['sources']}) == len(candidate['sources'])
    assert len({q['id'] for q in candidate['queries']}) == len(candidate['queries'])
    normalize = lambda value: ''.join(value.split()).casefold()
    dev_queries = {normalize(q['query']) for q in development_data['queries']}
    cur_queries = [normalize(q['query']) for q in candidate['queries']]
    assert len(set(cur_queries)) == len(cur_queries) and not dev_queries.intersection(cur_queries)
    assert not {s['text'] for s in development_data['sources']}.intersection(s['text'] for s in candidate['sources'])

    if other_candidate_data:
        other_queries = {normalize(q['query']) for q in other_candidate_data['queries']}
        # Overlap check is between distinct baseline generations (e.g. v1 vs v2)
        if candidate.get('parentDatasetSha256') is None and other_candidate_data.get('parentDatasetSha256') is None:
            assert not other_queries.intersection(cur_queries), "Overlap between independent candidate versions detected!"
            assert not {s['text'] for s in other_candidate_data['sources']}.intersection(s['text'] for s in candidate['sources'])

    aliases = {s['alias']: s for s in candidate['sources']}
    for query in candidate['queries']:
        assert query.get('labelReview') in {'PENDING_HUMAN_REVIEW', 'HUMAN_REVIEWED'}, f"Unknown query labelReview: {query.get('labelReview')}"
        assert set(query['scopes']) <= {'PROJECT', 'RESOURCE'} and query['scopes']
        assert set(query['expectedAliases']) <= aliases.keys()
        assert set(query['forbiddenAliases']) <= aliases.keys()
        assert not set(query['expectedAliases']).intersection(query['forbiddenAliases'])
        assert all(aliases[a]['kind'] not in {'STALE_NODE', 'PRIVATE_NODE'} for a in query['expectedAliases'])
        if query['category'] == 'MULTI_SOURCE':
            assert len(query['expectedAliases']) >= 2
        if any(aliases[a]['kind'] == 'RESOURCE' for a in query['expectedAliases']):
            assert 'RESOURCE' in query['scopes']

    # 6. Read-only review markdown verification
    review_md_path = DATA / review_md_name
    review_doc_present = review_md_path.exists()
    if review_doc_present:
        review_content = review_md_path.read_text(encoding='utf-8')
        for q in candidate['queries']:
            assert q['id'] in review_content, f"Query {q['id']} missing from review document {review_md_name}"

    # 7. Comprehensive 7-Point Human Approval Gate Verification
    gate_failures = []
    
    # Gate 1: All 68 queries individually reviewed (must be 'HUMAN_REVIEWED' per contract)
    unreviewed_queries = [q['id'] for q in candidate['queries'] if q.get('labelReview') != 'HUMAN_REVIEWED']
    if unreviewed_queries:
        gate_failures.append(f"Individual query reviews pending ({len(unreviewed_queries)} queries unreviewed, e.g. {unreviewed_queries[:3]})")
    
    # Gate 2: Genuine reviewer identity and timestamp
    reviewed_by = str(candidate.get('reviewedBy') or '').strip()
    reviewed_at = str(candidate.get('reviewedAt') or '').strip()
    placeholder_names = {'none', 'null', 'pending', 'ai', 'ai-agent', 'unassigned', ''}
    if not reviewed_by or reviewed_by.lower() in placeholder_names:
        gate_failures.append(f"Genuine human reviewer identity missing (got: '{reviewed_by}')")
    if not reviewed_at or reviewed_at.lower() in placeholder_names:
        gate_failures.append(f"Human review timestamp missing (got: '{reviewed_at}')")
    else:
        try:
            datetime.fromisoformat(reviewed_at.replace('Z', '+00:00'))
        except Exception:
            gate_failures.append(f"Human review timestamp is not a valid ISO-8601 instant (got: '{reviewed_at}')")
    
    # Gate 3: Candidate dataset hash consistency
    if candidate_actual_sha != freeze['datasetSha256']:
        gate_failures.append(f"Dataset SHA-256 mismatch (actual {candidate_actual_sha[:8]} vs frozen {freeze['datasetSha256'][:8]})")
        
    # Gate 4: Implementation and dependency hashes alignment
    if drifted_files:
        gate_failures.append(f"Implementation/dependency hashes drifted ({len(drifted_files)} files drifted)")
        
    # Gate 5: Quality acceptance threshold approval
    threshold_status = str(freeze.get('thresholdStatus', '')).upper()
    if 'PROPOSED' in threshold_status or 'NOT_APPROVED' in threshold_status or 'PENDING' in threshold_status:
        gate_failures.append(f"Quality acceptance thresholds unapproved (status: '{freeze.get('thresholdStatus')}')")
    elif threshold_status not in ('APPROVED_BEFORE_FIRST_RUN', 'APPROVED'):
        gate_failures.append(f"Unknown threshold status (status: '{freeze.get('thresholdStatus')}')")

    thresholds = freeze.get('proposedThresholds') or freeze.get('approvedThresholds') or {}
    for metric in ('macroSourceRecallAt8', 'firstRelevantMrr', 'multiSourceAllOfCoverage'):
        val = thresholds.get(metric)
        if val is None or not isinstance(val, (int, float)) or val < 0.0 or val > 1.0:
            gate_failures.append(f"Threshold for {metric} is invalid: {val} (must be in [0.0, 1.0])")
    if 'noAnswerZeroHitCorrectness' in thresholds and thresholds['noAnswerZeroHitCorrectness'] is not None:
        val = thresholds['noAnswerZeroHitCorrectness']
        if not isinstance(val, (int, float)) or val < 0.0 or val > 1.0:
            gate_failures.append(f"Threshold for noAnswerZeroHitCorrectness is invalid: {val}")
    if 'pureNoAnswerZeroHitCorrectness' in thresholds and thresholds['pureNoAnswerZeroHitCorrectness'] is not None:
        val = thresholds['pureNoAnswerZeroHitCorrectness']
        if not isinstance(val, (int, float)) or val < 0.0 or val > 1.0:
            gate_failures.append(f"Threshold for pureNoAnswerZeroHitCorrectness is invalid: {val}")
    for leak in ('staleSourceLeakCount', 'permissionLeakCount'):
        val = thresholds.get(leak)
        if val is None or not isinstance(val, int) or val < 0:
            gate_failures.append(f"Threshold for {leak} is invalid: {val} (must be non-negative integer)")
        
    # Gate 6: Zero tuning usage
    if candidate.get('tuningUse') != 'NEVER_USED':
        gate_failures.append(f"Dataset contaminated by tuning use (tuningUse: '{candidate.get('tuningUse')}')")
        
    # Gate 7: Zero prior execution history
    if candidate.get('executionStatus') != 'NOT_RUN':
        gate_failures.append(f"Execution history polluted (executionStatus: '{candidate.get('executionStatus')}')")

    human_status = str(candidate.get('humanReviewStatus', '')).strip()
    if human_status != 'APPROVED':
        gate_failures.append(f"Top-level humanReviewStatus is '{human_status}', not 'APPROVED'")

    labels_approved = (len(gate_failures) == 0 and human_status == 'APPROVED')

    if gate_failures:
        precheck_status = 'BLOCKED'
        status_reason = f"Execution gate BLOCKED by {len(gate_failures)} missing condition(s): " + "; ".join(gate_failures)
    else:
        precheck_status = 'PASS'
        status_reason = 'All 7 acceptance gate conditions fully satisfied; dataset verified and human approved.'

    return {
        'freezeFile': freeze_name,
        'datasetFile': candidate_name,
        'reviewDocument': review_md_name,
        'status': precheck_status,
        'statusReason': status_reason,
        'gateFailures': gate_failures,
        'datasetRole': candidate['datasetRole'],
        'queryCount': len(candidate['queries']),
        'sourceCount': len(aliases),
        'categories': dict(Counter(q['category'] for q in candidate['queries'])),
        'candidateSha256': freeze['datasetSha256'],
        'freezeSha256': sha(DATA / freeze_name),
        'executedQueries': 0,
        'executionStatus': 'NOT_RUN',
        'labelsApproved': labels_approved,
        'humanReviewStatus': human_status,
        'humanReviewDocumentPresent': review_doc_present,
        'hashDriftCount': len(drifted_files),
        'hashDriftList': drifted_files,
    }


def audit(version='all', strict=False):
    development = json.loads((DATA / 'chinese-queries.v1.json').read_text(encoding='utf-8'))
    assert development['datasetRole'] == 'DEVELOPMENT_CALIBRATION'
    assert not development['calibrationHistory']['independentAcceptanceEligible']

    cand_v1 = json.loads((DATA / 'acceptance-candidate.v1.json').read_text(encoding='utf-8'))
    cand_v2 = json.loads((DATA / 'acceptance-candidate.v2.json').read_text(encoding='utf-8'))

    results = {}
    if version in ('all', 'v1'):
        results['v1_regression_asset'] = audit_single_asset(
            'acceptance-freeze.v1.json', 'acceptance-candidate.v1.json', 'ACCEPTANCE_HUMAN_REVIEW.md',
            development, other_candidate_data=cand_v2
        )
    if version in ('all', 'v2'):
        results['v2_independent_asset'] = audit_single_asset(
            'acceptance-freeze.v2.json', 'acceptance-candidate.v2.json', 'ACCEPTANCE_HUMAN_REVIEW.v2.md',
            development, other_candidate_data=cand_v1
        )
    if version in ('all', 'v2_revised', 'v2-revised'):
        cand_v2_rev = json.loads((DATA / 'acceptance-candidate.v2-revised.json').read_text(encoding='utf-8'))
        results['v2_revised_asset'] = audit_single_asset(
            'acceptance-freeze.v2-revised.json', 'acceptance-candidate.v2-revised.json', 'ACCEPTANCE_HUMAN_REVIEW.v2-revised.md',
            development, other_candidate_data=cand_v1
        )
    if version in ('all', 'v2_1', 'v2-1', 'v2.1'):
        cand_v21 = json.loads((DATA / 'acceptance-candidate.v2.1.json').read_text(encoding='utf-8'))
        results['v2_1_asset'] = audit_single_asset(
            'acceptance-freeze.v2.1.json', 'acceptance-candidate.v2.1.json', 'ACCEPTANCE_HUMAN_REVIEW.v2.1.md',
            development, other_candidate_data=cand_v1
        )
    if version in ('all', 'v2_2', 'v2-2', 'v2.2'):
        cand_v22 = json.loads((DATA / 'acceptance-candidate.v2.2.json').read_text(encoding='utf-8'))
        results['v2_2_asset'] = audit_single_asset(
            'acceptance-freeze.v2.2.json', 'acceptance-candidate.v2.2.json', 'ACCEPTANCE_HUMAN_REVIEW.v2.2.md',
            development, other_candidate_data=cand_v1
        )

    # Consolidated report compatible with existing evidence consumers
    primary_v1 = results.get('v1_regression_asset', {})
    primary_v2 = results.get('v2_independent_asset', {})
    primary_v2_rev = results.get('v2_revised_asset', {})
    primary_v21 = results.get('v2_1_asset', {})
    primary_v22 = results.get('v2_2_asset', {})
    
    # Overall status is PASS only if all requested assets PASS
    overall_status = 'PASS' if all(r['status'] == 'PASS' for r in results.values()) else 'BLOCKED'

    combined_report = {
        'recordedAt': datetime.now(timezone.utc).isoformat(),
        'status': overall_status,
        'governanceDecision': 'BLOCKED; Asset A retired to REVIEWED_REGRESSION awaiting review; Asset B/C/D code-aligned but PENDING human label & threshold approval.',
        'assets': results,
        # Backward-compatible fields mapped to primary asset
        'queryCount': primary_v22.get('queryCount', primary_v21.get('queryCount', primary_v2_rev.get('queryCount', primary_v1.get('queryCount', 68)))),
        'sourceCount': primary_v22.get('sourceCount', primary_v21.get('sourceCount', primary_v2_rev.get('sourceCount', primary_v1.get('sourceCount', 28)))),
        'categories': primary_v22.get('categories', primary_v21.get('categories', primary_v2_rev.get('categories', primary_v1.get('categories', {})))),
        'candidateSha256': primary_v22.get('candidateSha256', primary_v21.get('candidateSha256', primary_v2_rev.get('candidateSha256', primary_v1.get('candidateSha256')))),
        'freezeSha256': primary_v22.get('freezeSha256', primary_v21.get('freezeSha256', primary_v2_rev.get('freezeSha256', primary_v1.get('freezeSha256')))),
        'executedQueries': 0,
        'executionStatus': 'NOT_RUN',
        'labelsApproved': False,
        'humanReviewStatus': 'PENDING',
        'hashDriftCount': primary_v22.get('hashDriftCount', primary_v21.get('hashDriftCount', primary_v2_rev.get('hashDriftCount', 0))),
        'hashDriftList': primary_v22.get('hashDriftList', primary_v21.get('hashDriftList', primary_v2_rev.get('hashDriftList', []))),
        'semanticPrecheck': [
            'Near-no-answer asks unsupported quantities/policies; related context is not sufficient to answer.',
            'Multi-source labels are all-of, not any-hit; reviewers must check each source is necessary.',
            'Repository-grounded expressions are AI paraphrases, not captured customer/user transcripts.',
            'Shared Question/re-answer, Focus/route and restart/UNKNOWN topics overlap semantically; humans must check alternative relevant sources.',
            'Near-no-answer labels and multi-source labels require particular human attention.',
            'No exact overlap is not proof of statistical independence; same project domain and AI author remain limitations.'
        ],
        'rootLimit': 'AI static/semantic precheck is not human annotation or retrieval-quality acceptance.'
    }

    evidence_path = ROOT / 'docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json'
    evidence_path.write_text(json.dumps(combined_report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    print("=== Spec Agent Retrieval Acceptance Static Audit ===")
    for key, res in results.items():
        print(f"\n[{key.upper()}]")
        print(f" Freeze File     : {res['freezeFile']} (SHA: {res['freezeSha256'][:16]}...)")
        print(f" Dataset File    : {res['datasetFile']} (SHA: {res['candidateSha256'][:16]}...)")
        print(f" Dataset Role    : {res['datasetRole']}")
        print(f" Status          : {res['status']}")
        print(f" Status Reason   : {res['statusReason']}")
        print(f" Hash Drifts     : {res['hashDriftCount']}")
        print(f" Executed Queries: {res['executedQueries']} ({res['executionStatus']})")
        print(f" Human Approval  : {res['humanReviewStatus']} (Approved: {res['labelsApproved']})")
    print(f"\nOverall Precheck Status : {overall_status}")
    print("====================================================")

    if strict and overall_status != 'PASS':
        sys.exit(1)
    return combined_report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Static precheck for independent retrieval acceptance.")
    parser.add_argument("--version", choices=['all', 'v1', 'v2', 'v2_revised', 'v2-revised', 'v2.1', 'v2_1', 'v2-1', 'v2.2', 'v2_2', 'v2-2'], default='all', help="Audit specific asset or all.")
    parser.add_argument("--strict", action="store_true", help="Fail with non-zero exit code if status is not PASS.")
    args = parser.parse_args()
    audit(version=args.version, strict=args.strict)
