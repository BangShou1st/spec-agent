"""Static precheck only: never runs queries, tunes config, or approves human labels.
ReadOnly audit tool: will never overwrite human review markdown.
Supports both Asset A (historical v1 regression candidate) and Asset B (new v2 independent acceptance candidate).
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
    actual_req_sha = sha(ROOT / 'agent-brain/requirements.lock')
    if freeze['requirementsLockSha256'] != actual_req_sha:
        drifted_files.append({
            'file': 'agent-brain/requirements.lock',
            'frozenHash': freeze['requirementsLockSha256'],
            'actualHash': actual_req_sha,
            'reason': 'Added socksio==1.0.0 for proxy isolation (commit 685bb05)'
        })

    for rel_path, expected_hash in freeze['codeHashes'].items():
        actual_code_sha = sha(ROOT / rel_path)
        if expected_hash != actual_code_sha:
            drifted_files.append({
                'file': rel_path,
                'frozenHash': expected_hash,
                'actualHash': actual_code_sha,
                'reason': 'Historical engine/environment updates (commits 210d288, 520a8c7, 66f2309, c942366, 685bb05)'
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
        assert not other_queries.intersection(cur_queries), "Overlap between candidate versions detected!"
        assert not {s['text'] for s in other_candidate_data['sources']}.intersection(s['text'] for s in candidate['sources'])

    aliases = {s['alias']: s for s in candidate['sources']}
    for query in candidate['queries']:
        assert query['labelReview'] == 'PENDING_HUMAN_REVIEW'
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
    
    # Gate 1: All 68 queries individually reviewed (not just top-level)
    unreviewed_queries = [q['id'] for q in candidate['queries'] if q.get('labelReview') == 'PENDING_HUMAN_REVIEW']
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
        
    # Gate 6: Zero tuning usage
    if candidate.get('tuningUse') != 'NEVER_USED':
        gate_failures.append(f"Dataset contaminated by tuning use (tuningUse: '{candidate.get('tuningUse')}')")
        
    # Gate 7: Zero prior execution history
    if candidate.get('executionStatus') != 'NOT_RUN':
        gate_failures.append(f"Execution history polluted (executionStatus: '{candidate.get('executionStatus')}')")

    human_status = candidate['humanReviewStatus']
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

    # Consolidated report compatible with existing evidence consumers
    primary_v1 = results.get('v1_regression_asset', {})
    primary_v2 = results.get('v2_independent_asset', {})
    
    # Overall status is PASS only if all requested assets PASS
    overall_status = 'PASS' if all(r['status'] == 'PASS' for r in results.values()) else 'BLOCKED'

    combined_report = {
        'recordedAt': datetime.now(timezone.utc).isoformat(),
        'status': overall_status,
        'governanceDecision': 'BLOCKED; Asset A retired to REVIEWED_REGRESSION awaiting review; Asset B code-aligned but PENDING human label & threshold approval.',
        'assets': results,
        # Backward-compatible fields mapped to v1
        'queryCount': primary_v1.get('queryCount', 68),
        'sourceCount': primary_v1.get('sourceCount', 28),
        'categories': primary_v1.get('categories', {}),
        'candidateSha256': primary_v1.get('candidateSha256'),
        'freezeSha256': primary_v1.get('freezeSha256'),
        'executedQueries': 0,
        'executionStatus': 'NOT_RUN',
        'labelsApproved': False,
        'humanReviewStatus': 'PENDING',
        'hashDriftCount': primary_v1.get('hashDriftCount', 0),
        'hashDriftList': primary_v1.get('hashDriftList', []),
        'semanticPrecheck': [
            'Near-no-answer asks unsupported quantities/policies; related context is not sufficient to answer.',
            'Multi-source labels are all-of, not any-hit; reviewers must check each source is necessary.',
            'Repository-grounded expressions are AI paraphrases, not captured customer/user transcripts.',
            'Shared Question/re-answer, Focus/route and restart/UNKNOWN topics overlap semantically; humans must check alternative relevant sources.',
            'Eight near-no-answer labels and eight multi-source labels require particular human attention.',
            'No exact overlap is not proof of statistical independence; same project domain and AI author remain limitations.'
        ],
        'rootLimit': 'AI static/semantic precheck is not human annotation or retrieval-quality acceptance.'
    }

    evidence_path = ROOT / 'docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json'
    evidence_path.write_text(json.dumps(combined_report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    print("=== Spec Agent Retrieval Acceptance Static Audit (Dual Asset) ===")
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
    print("==================================================================")

    if strict and overall_status != 'PASS':
        sys.exit(1)
    return combined_report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Static precheck for independent retrieval acceptance.")
    parser.add_argument("--version", choices=['all', 'v1', 'v2'], default='all', help="Audit specific asset or all.")
    parser.add_argument("--strict", action="store_true", help="Fail with non-zero exit code if status is not PASS.")
    args = parser.parse_args()
    audit(version=args.version, strict=args.strict)


