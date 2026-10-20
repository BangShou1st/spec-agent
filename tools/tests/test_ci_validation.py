"""Guard mode boundaries and truthful summaries without executing production code."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("validation_summary", ROOT / "tools/ci/validation_summary.py")
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


def results(full=False):
    return {"validation-mode": {"result": "success"},
            **{name: {"result": "success" if full or name in summary.INDEPENDENT else "skipped"}
               for name in summary.INDEPENDENT + summary.EXTERNAL}}


def test_independent_success_reports_external_skip_and_unmeasured_live():
    report, failures = summary.summarize(results(), False)
    assert failures == []
    assert report.count("| PASS |") == 4
    assert report.count("| SKIPPED_EXTERNAL_DEPENDENCY |") == 4
    assert "NOT_MEASURED" in report


def test_real_failure_cancellation_missing_or_unexpected_skip_never_passes():
    for result in ("failure", "cancelled", "skipped", "missing"):
        needs = results()
        needs["brain"] = {"result": result}
        report, failures = summary.summarize(needs, False)
        assert failures
        assert "| brain | BLOCKED |" in report


def test_full_mode_requires_external_jobs_to_execute():
    assert summary.summarize(results(True), True)[1] == []
    assert len(summary.summarize(results(), True)[1]) == 4


def test_missing_scope_job_cannot_produce_a_successful_summary():
    needs = results()
    del needs["validation-mode"]
    assert summary.summarize(needs, False)[1]


def test_independent_mode_rejects_accidentally_executed_external_jobs():
    assert len(summary.summarize(results(True), False)[1]) == 4


def test_workflow_keeps_production_dependency_and_explicit_manual_full_mode():
    workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    for job in summary.EXTERNAL:
        assert f"  {job}:\n    needs: validation-mode\n    if: needs.validation-mode.outputs.full == 'true'" in workflow
    assert "continue-on-error" not in workflow
    assert "default: false" in workflow
    assert "github.event_name == 'workflow_dispatch' && inputs.full_validation == true" in workflow
    build = (ROOT / "backend/build.gradle.kts").read_text(encoding="utf-8")
    assert 'implementation("com.sharedlibs:opencode-zen-spring-boot-starter:1.2.0")' in build
    assert "skipZenStarter" not in build
