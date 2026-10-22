"""Test for static audit tool behavior, protections, and gate validation."""
import copy
import hashlib
import json
from pathlib import Path
import pytest

from scripts.audit_retrieval_acceptance import audit, audit_single_asset, ROOT, DATA


def test_audit_retrieval_acceptance_reports_blocked_and_preserves_review():
    review_v1 = DATA / 'ACCEPTANCE_HUMAN_REVIEW.md'
    review_v2 = DATA / 'ACCEPTANCE_HUMAN_REVIEW.v2.md'
    review_v2_rev = DATA / 'ACCEPTANCE_HUMAN_REVIEW.v2-revised.md'
    assert review_v1.exists(), "Review markdown v1 should exist before test"
    assert review_v2.exists(), "Review markdown v2 should exist before test"
    assert review_v2_rev.exists(), "Review markdown v2-revised should exist before test"
    mtime_v1_before = review_v1.stat().st_mtime_ns
    mtime_v2_before = review_v2.stat().st_mtime_ns
    mtime_v2_rev_before = review_v2_rev.stat().st_mtime_ns
    content_v1_before = review_v1.read_text(encoding='utf-8')
    content_v2_before = review_v2_rev.read_text(encoding='utf-8')

    report = audit(version='all', strict=False)

    # 1. Verification of consolidated audit output
    assert report['status'] == 'BLOCKED'
    assert report['executedQueries'] == 0
    assert report['executionStatus'] == 'NOT_RUN'
    assert 'assets' in report
    assert 'v1_regression_asset' in report['assets']
    assert 'v2_independent_asset' in report['assets']
    assert 'v2_revised_asset' in report['assets']

    # 2. Verification of Asset A (v1 regression asset)
    v1_res = report['assets']['v1_regression_asset']
    assert v1_res['status'] == 'BLOCKED'
    assert v1_res['hashDriftCount'] == 6
    assert any(d['file'] == 'backend/src/main/java/com/specagent/retrieval/protocol/RetrievalStore.java' for d in v1_res['hashDriftList'])
    assert v1_res['executedQueries'] == 0
    assert v1_res['humanReviewStatus'] == 'PENDING'

    # 3. Verification of Asset B (v2 independent asset)
    v2_res = report['assets']['v2_independent_asset']
    assert v2_res['status'] == 'BLOCKED'
    assert v2_res['executedQueries'] == 0
    assert v2_res['humanReviewStatus'] == 'PENDING'
    assert v2_res['labelsApproved'] is False
    assert v2_res['datasetRole'] == 'INDEPENDENT_ACCEPTANCE_CANDIDATE'

    # 4. Verification of Asset C (v2-revised asset)
    v2_rev_res = report['assets']['v2_revised_asset']
    assert v2_rev_res['status'] == 'BLOCKED'
    assert v2_rev_res['hashDriftCount'] == 0
    assert v2_rev_res['executedQueries'] == 0
    assert v2_rev_res['humanReviewStatus'] == 'PENDING'
    assert v2_rev_res['labelsApproved'] is False
    assert v2_rev_res['datasetRole'] == 'INDEPENDENT_ACCEPTANCE_CANDIDATE'

    # 5. Verification of non-overwrite protection on all review documents
    assert review_v1.stat().st_mtime_ns == mtime_v1_before, "Audit should not modify ACCEPTANCE_HUMAN_REVIEW.md"
    assert review_v2.stat().st_mtime_ns == mtime_v2_before, "Audit should not modify ACCEPTANCE_HUMAN_REVIEW.v2.md"
    assert review_v2_rev.stat().st_mtime_ns == mtime_v2_rev_before, "Audit should not modify ACCEPTANCE_HUMAN_REVIEW.v2-revised.md"


def test_in_memory_approved_candidate_passes_audit_gate_without_conflict(tmp_path, monkeypatch):
    """Test that a genuinely approved dataset passes all 7 gates in Python static precheck without assertion conflicts."""
    cand_path = DATA / 'acceptance-candidate.v2-revised.json'
    freeze_path = DATA / 'acceptance-freeze.v2-revised.json'
    review_path = DATA / 'ACCEPTANCE_HUMAN_REVIEW.v2-revised.md'

    cand_data = json.loads(cand_path.read_text(encoding='utf-8'))
    freeze_data = json.loads(freeze_path.read_text(encoding='utf-8'))

    # Synthetic approval in temporary directory only (never written to production candidates)
    cand_data['humanReviewStatus'] = 'APPROVED'
    cand_data['reviewedBy'] = 'Auditor Lead Test'
    cand_data['reviewedAt'] = '2026-10-22T20:00:00+00:00'
    for q in cand_data['queries']:
        q['labelReview'] = 'HUMAN_REVIEWED'

    temp_cand_bytes = json.dumps(cand_data, ensure_ascii=False, indent=2).encode('utf-8')
    temp_cand_sha = hashlib.sha256(temp_cand_bytes).hexdigest()

    temp_cand_file = tmp_path / 'temp-approved-candidate.json'
    temp_freeze_file = tmp_path / 'temp-approved-freeze.json'

    freeze_data['datasetFile'] = temp_cand_file.name
    freeze_data['datasetSha256'] = temp_cand_sha
    freeze_data['thresholdStatus'] = 'APPROVED_BEFORE_FIRST_RUN'

    temp_freeze_bytes = json.dumps(freeze_data, ensure_ascii=False, indent=2).encode('utf-8')

    temp_cand_file.write_bytes(temp_cand_bytes)
    temp_freeze_file.write_bytes(temp_freeze_bytes)

    # Monkeypatch DATA to tmp_path for isolated test
    monkeypatch.setattr('scripts.audit_retrieval_acceptance.DATA', tmp_path)
    (tmp_path / 'review.md').write_text(review_path.read_text(encoding='utf-8'), encoding='utf-8')

    development = json.loads((DATA / 'chinese-queries.v1.json').read_text(encoding='utf-8'))

    res = audit_single_asset(
        temp_freeze_file.name,
        temp_cand_file.name,
        'review.md',
        development
    )

    assert res['status'] == 'PASS', f"Expected PASS for approved candidate, got: {res['statusReason']}"
    assert res['labelsApproved'] is True
    assert res['gateFailures'] == []
