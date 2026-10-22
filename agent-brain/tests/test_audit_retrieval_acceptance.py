"""Test for static audit tool behavior and protections."""
import json
from pathlib import Path
import pytest

from scripts.audit_retrieval_acceptance import audit, ROOT, DATA


def test_audit_retrieval_acceptance_reports_blocked_and_preserves_review():
    review_file = DATA / 'ACCEPTANCE_HUMAN_REVIEW.md'
    assert review_file.exists(), "Review markdown should exist before test"
    mtime_before = review_file.stat().st_mtime_ns
    content_before = review_file.read_text(encoding='utf-8')

    report = audit(strict=False)

    # 1. Verification of audit output
    assert report['status'] == 'BLOCKED'
    assert report['queryCount'] == 68
    assert report['sourceCount'] == 28
    assert report['executedQueries'] == 0
    assert report['executionStatus'] == 'NOT_RUN'
    assert report['humanReviewStatus'] == 'PENDING'
    assert report['labelsApproved'] is False
    assert report['hashDriftCount'] == 6
    assert any(d['file'] == 'backend/src/main/java/com/specagent/retrieval/protocol/RetrievalStore.java' for d in report['hashDriftList'])

    # 2. Verification of non-overwrite protection
    mtime_after = review_file.stat().st_mtime_ns
    content_after = review_file.read_text(encoding='utf-8')
    assert mtime_before == mtime_after, "Audit should not modify or rewrite ACCEPTANCE_HUMAN_REVIEW.md"
    assert content_before == content_after, "Content of review markdown must stay byte-identical"
