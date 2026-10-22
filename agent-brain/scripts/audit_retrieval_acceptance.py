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
try:
    from scripts.retrieval_acceptance_contract import validate_contract
except ModuleNotFoundError:
    from retrieval_acceptance_contract import validate_contract


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

    # Same strict contract exercised by the offline Java parity corpus.
    gate_failures = validate_contract(candidate, (DATA / candidate_name).read_bytes(), freeze, ROOT)
    if drifted_files:
        gate_failures.append(f"Implementation/dependency hashes drifted ({len(drifted_files)} files drifted)")
    human_status = str(candidate.get('humanReviewStatus', '')).strip()

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


def audit(version='all', strict=False, output_path=None):
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

    if output_path is not None:
        Path(output_path).write_text(json.dumps(combined_report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

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
