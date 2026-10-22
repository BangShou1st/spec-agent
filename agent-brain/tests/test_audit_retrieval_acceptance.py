"""Test for static audit tool behavior and protections."""
import json
from pathlib import Path
import pytest

from scripts.audit_retrieval_acceptance import audit, ROOT, DATA


def test_audit_retrieval_acceptance_reports_blocked_and_preserves_review():
    review_v1 = DATA / 'ACCEPTANCE_HUMAN_REVIEW.md'
    review_v2 = DATA / 'ACCEPTANCE_HUMAN_REVIEW.v2.md'
    assert review_v1.exists(), "Review markdown v1 should exist before test"
    assert review_v2.exists(), "Review markdown v2 should exist before test"
    mtime_v1_before = review_v1.stat().st_mtime_ns
    mtime_v2_before = review_v2.stat().st_mtime_ns
    content_v1_before = review_v1.read_text(encoding='utf-8')
    content_v2_before = review_v2.read_text(encoding='utf-8')

    report = audit(version='all', strict=False)

    # 1. Verification of consolidated audit output
    assert report['status'] == 'BLOCKED'
    assert report['executedQueries'] == 0
    assert report['executionStatus'] == 'NOT_RUN'
    assert 'assets' in report
    assert 'v1_regression_asset' in report['assets']
    assert 'v2_independent_asset' in report['assets']

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
    assert v2_res['hashDriftCount'] == 0  # 100% matched with current code and lock!
    assert v2_res['executedQueries'] == 0
    assert v2_res['humanReviewStatus'] == 'PENDING'
    assert v2_res['labelsApproved'] is False
    assert v2_res['datasetRole'] == 'INDEPENDENT_ACCEPTANCE_CANDIDATE'

    # 4. Verification of non-overwrite protection on both review documents
    assert review_v1.stat().st_mtime_ns == mtime_v1_before, "Audit should not modify ACCEPTANCE_HUMAN_REVIEW.md"
    assert review_v2.stat().st_mtime_ns == mtime_v2_before, "Audit should not modify ACCEPTANCE_HUMAN_REVIEW.v2.md"
    assert review_v1.read_text(encoding='utf-8') == content_v1_before
    assert review_v2.read_text(encoding='utf-8') == content_v2_before

