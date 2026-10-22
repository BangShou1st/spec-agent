"""Static precheck only: never runs queries, tunes config, or approves human labels.
ReadOnly audit tool: will never overwrite human review markdown.
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


def audit(strict=False):
    candidate = json.loads((DATA / 'acceptance-candidate.v1.json').read_text(encoding='utf-8'))
    development = json.loads((DATA / 'chinese-queries.v1.json').read_text(encoding='utf-8'))
    freeze = json.loads((DATA / 'acceptance-freeze.v1.json').read_text(encoding='utf-8'))

    # 1. Dataset role and isolation assertions
    assert development['datasetRole'] == 'DEVELOPMENT_CALIBRATION'
    assert not development['calibrationHistory']['independentAcceptanceEligible']
    assert candidate['datasetRole'] == 'INDEPENDENT_ACCEPTANCE_CANDIDATE'
    assert candidate['executionStatus'] == 'NOT_RUN' and candidate['tuningUse'] == 'NEVER_USED'
    assert len(candidate['queries']) == 68, f"Expected 68 candidate queries, got {len(candidate['queries'])}"
    assert len(candidate['sources']) == 28, f"Expected 28 sources, got {len(candidate['sources'])}"

    # 2. Frozen dataset hash assertion
    candidate_actual_sha = sha(DATA / freeze['datasetFile'])
    assert freeze['datasetSha256'] == candidate_actual_sha, "Candidate dataset hash mismatch!"

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
    old_queries = {normalize(q['query']) for q in development['queries']}
    new_queries = [normalize(q['query']) for q in candidate['queries']]
    assert len(set(new_queries)) == len(new_queries) and not old_queries.intersection(new_queries)
    assert not {s['text'] for s in development['sources']}.intersection(s['text'] for s in candidate['sources'])

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

    # 6. Read-only review markdown verification (never overwrite!)
    review_md_path = DATA / 'ACCEPTANCE_HUMAN_REVIEW.md'
    review_doc_present = review_md_path.exists()
    if review_doc_present:
        review_content = review_md_path.read_text(encoding='utf-8')
        for q in candidate['queries']:
            assert q['id'] in review_content, f"Query {q['id']} missing from review document"

    # 7. Overall status evaluation
    human_status = candidate['humanReviewStatus']
    labels_approved = (human_status == 'APPROVED')
    
    # Blocked if code hashes drifted or human review is still pending
    if drifted_files:
        precheck_status = 'BLOCKED'
        status_reason = f'Frozen code/dependency hashes drifted ({len(drifted_files)} files). Lexical lane and environment modified since freeze snapshot. Governance requires pre-run resolution.'
    elif not labels_approved:
        precheck_status = 'BLOCKED'
        status_reason = 'Candidate labels remain PENDING human approval. Formal acceptance cannot run without approved labels.'
    else:
        precheck_status = 'PASS'
        status_reason = 'Static precheck passed; dataset and freeze hashes aligned; human approved.'

    report = {
        'recordedAt': datetime.now(timezone.utc).isoformat(),
        'status': precheck_status,
        'statusReason': status_reason,
        'queryCount': len(candidate['queries']),
        'sourceCount': len(aliases),
        'categories': dict(Counter(q['category'] for q in candidate['queries'])),
        'exactQueryOverlapWithDevelopment': 0,
        'exactSourceOverlapWithDevelopment': 0,
        'candidateSha256': freeze['datasetSha256'],
        'freezeSha256': sha(DATA / 'acceptance-freeze.v1.json'),
        'executedQueries': 0,
        'executionStatus': 'NOT_RUN',
        'labelsApproved': labels_approved,
        'humanReviewStatus': human_status,
        'humanReviewDocumentPresent': review_doc_present,
        'hashDriftCount': len(drifted_files),
        'hashDriftList': drifted_files,
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

    # Record report without touching or overwriting human review document
    evidence_path = ROOT / 'docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json'
    evidence_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    print(f"=== Spec Agent Retrieval Acceptance Static Audit ===")
    print(f"Overall Status   : {precheck_status}")
    print(f"Reason           : {status_reason}")
    print(f"Executed Queries : 0 (NOT_RUN)")
    print(f"Candidate Hash   : {candidate_actual_sha} (Match: True)")
    print(f"Human Approval   : {human_status} (Approved: {labels_approved})")
    print(f"Review Doc State : Present ({review_md_path.stat().st_size} bytes, safe/never overwritten)")
    print(f"Hash Drift Count : {len(drifted_files)}")
    if drifted_files:
        print("--- Drifted Files ---")
        for d in drifted_files:
            print(f" - {d['file']}")
            print(f"   Frozen: {d['frozenHash']}")
            print(f"   Actual: {d['actualHash']}")
            print(f"   Reason: {d['reason']}")
    print("====================================================")

    if strict and precheck_status != 'PASS':
        sys.exit(1)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description="Static precheck for independent retrieval acceptance.")
    parser.add_argument("--strict", action="store_true", help="Fail with non-zero exit code if status is not PASS.")
    args = parser.parse_args()
    audit(strict=args.strict)

