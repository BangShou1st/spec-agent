# Human Review Decision Checklist (v2.1)

> Purpose: Mandatory checklist for designated human business auditor before signing off acceptance candidate v2.1.

## Gate Checklist
- [ ] **Gate 1: Query Label Verification**
  - [ ] All 49 positive queries have necessary and sufficient expectedAliases.
  - [ ] All 8 NEAR_NO_ANSWER queries confirmed to have adjacent context but lacking sufficient answer.
  - [ ] All 3 NO_ANSWER queries confirmed completely unmentioned in corpus.
  - [ ] All 4 STALE queries and 4 PERMISSION queries confirmed to target revoked or tenant-isolated data.
- [ ] **Gate 2: Reviewer Authenticity**
  - [ ] Real human name and ISO-8601 timestamp entered (no automated placeholders).
- [ ] **Gate 3: Dataset SHA-256 Alignment**
  - [ ] Candidate SHA-256 matches freeze (`3162ce2b67e15f3ad31ab277e0ddaf56700cc609d0ddec386d0a9c232088e0f4`).
- [ ] **Gate 4: Code & Lock Hashes**
  - [ ] 17 tracked code files and requirements.lock verified without drift.
- [ ] **Gate 5: Threshold Approval**
  - [ ] Thresholds explicitly set to APPROVED_BEFORE_FIRST_RUN by business owner.
- [ ] **Gate 6 & 7: Contamination Checks**
  - [ ] tuningUse confirmed NEVER_USED.
  - [ ] executionStatus confirmed NOT_RUN.
