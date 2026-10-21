"""Guard mode boundaries and truthful summaries without executing production code."""
import importlib.util
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("validation_summary", ROOT / "tools/ci/validation_summary.py")
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)

WORKFLOW = ROOT / ".github/workflows/ci.yml"


def workflow_text():
    """Line-ending independent view of the workflow file."""
    return WORKFLOW.read_text(encoding="utf-8").replace("\r\n", "\n")
BUILD = ROOT / "backend/build.gradle.kts"


def results(full=False):
    return {"validation-mode": {"result": "success", "outputs": {"full": str(full).lower()}},
            **{name: {"result": "success" if full or name in summary.INDEPENDENT else "skipped"}
               for name in summary.INDEPENDENT + summary.EXTERNAL}}


def test_independent_success_reports_external_skip_and_unmeasured_live():
    report, failures = summary.summarize(results(), False)
    assert failures == []
    assert report.count("| PASS |") == 4
    assert report.count("| NOT RUN |") == 4
    assert "Live provider suites: SKIPPED_EXTERNAL_DEPENDENCY" in report


def test_real_failure_cancellation_missing_or_unexpected_skip_never_passes():
    for full in (False, True):
        for name in summary.INDEPENDENT + summary.EXTERNAL:
            for result, status in (("failure", "FAIL"), ("cancelled", "BLOCKED"),
                                   ("skipped", "NOT RUN"), ("missing", "NOT RUN")):
                if not full and name in summary.EXTERNAL and result == "skipped":
                    continue
                needs = results(full)
                needs[name] = {"result": result}
                report, failures = summary.summarize(needs, full)
                assert failures
                assert f"| {name} | {status} |" in report


def test_full_mode_requires_external_jobs_to_execute():
    assert summary.summarize(results(True), True)[1] == []
    needs = results()
    needs["validation-mode"]["outputs"]["full"] = "true"
    assert len(summary.summarize(needs, True)[1]) == 4


def test_missing_scope_job_cannot_produce_a_successful_summary():
    needs = results()
    del needs["validation-mode"]
    assert summary.summarize(needs, False)[1]


def test_independent_mode_rejects_accidentally_executed_external_jobs():
    needs = results(True)
    needs["validation-mode"]["outputs"]["full"] = "false"
    assert len(summary.summarize(needs, False)[1]) == 4


def test_scope_output_must_be_explicit_valid_and_consistent():
    for full in (False, True):
        for selected in (None, "", "invalid", str(not full).lower()):
            needs = results(full)
            needs["validation-mode"]["outputs"] = {"full": selected}
            assert summary.summarize(needs, full)[1]


def test_pr_trigger_and_always_running_summary_remain_enabled():
    workflow = WORKFLOW.read_text(encoding="utf-8")
    assert "\n  pull_request:\n" in workflow
    assert "  validation-summary:\n    if: always()" in workflow
    assert "needs: [validation-mode, frontend, brain, backend-independent, contracts-static, backend, eval-baseline, cross-language, e2e]" in workflow


def test_workflow_uses_public_dependency_and_defaults_to_full_validation():
    workflow = WORKFLOW.read_text(encoding="utf-8")
    for job in summary.EXTERNAL:
        assert f"  {job}:\n    needs: validation-mode\n    if: needs.validation-mode.outputs.full == 'true'" in workflow
    assert "continue-on-error" not in workflow
    assert "default: true" in workflow
    assert "github.event_name != 'workflow_dispatch' || inputs.full_validation == true" in workflow
    build = BUILD.read_text(encoding="utf-8")
    assert 'implementation("com.github.BangShou1st.opencode-zen:opencode-zen-spring-boot-starter:v1.4.2")' in build
    assert "skipZenStarter" not in build


def test_push_trigger_covers_the_business_line_and_keeps_the_existing_branches():
    assert branch_list("push") == ["main", "system-bugfix-v1", "dev"]


def test_pull_request_scope_is_limited_to_the_validated_branches():
    assert branch_list("pull_request") == ["main", "dev"]


def branch_list(section):
    """Branches declared under a trigger section, tolerating comments and CRLF."""
    import re as _re
    lines = WORKFLOW.read_text(encoding="utf-8").replace("\r\n", "\n").splitlines()
    start = lines.index("  %s:" % section)
    collected, in_branches = [], False
    for line in lines[start + 1:]:
        if _re.match(r"^  \S", line):
            break
        if line.startswith("    branches:"):
            in_branches = True
            continue
        if in_branches:
            if line.startswith("      - "):
                collected.append(line[len("      - "):].strip())
            elif line.strip() and not line.startswith("      #"):
                break
    return collected

def test_no_unreviewed_wide_test_selection_remains_in_the_workflow():
    workflow = WORKFLOW.read_text(encoding="utf-8")
    # The task graph rejects a wide `test` selection: the audited offline
    # selection and the explicitly opted-in database selection are the only
    # allowed entry points.
    assert "gradlew cleanTest test\n" not in workflow
    assert not re.search(r"gradlew\s+cleanTest\s+test\s*$", workflow, re.M)
    assert "gradlew cleanTest testOffline" in workflow
    assert "gradlew test testDatabaseIntegration -PdatabaseIntegration=true" in workflow


def test_every_database_backed_selection_opts_in_explicitly():
    workflow = workflow_text()
    for task in ("evalBFast", "evalRetrievalFast", "testCrossLanguage", "testDatabaseIntegration"):
        for line in workflow.splitlines():
            if f"gradlew {task}" in line:
                assert "-PdatabaseIntegration=true" in line, (
                    f"{task} must opt into the database selection explicitly")


def test_database_jobs_use_the_isolated_target_and_never_the_historical_database():
    workflow = workflow_text()
    assert "spec_agent_p3b_live_ci" in workflow
    # The guard refuses spec_agent_test as a live target; it may only appear as
    # the administrative database the isolated database is created from.
    assert "SPEC_AGENT_TEST_DB_URL" in workflow
    for line in workflow.splitlines():
        if "SPEC_AGENT_TEST_DB_URL" in line:
            assert "steps.isolated-db.outputs.url" in line
    for line in workflow.splitlines():
        if "SPRING_DATASOURCE_URL" in line:
            assert "steps.isolated-db.outputs.url" in line


def test_the_task_graph_guard_and_its_selections_are_present():
    build = BUILD.read_text(encoding="utf-8")
    assert "Unreviewed test selection" in build
    assert 'tasks.register<Test>("testOffline")' in build
    assert 'tasks.register<Test>("testDatabaseIntegration")' in build
    assert "gradle.taskGraph.whenReady" in build
    assert 'gradleProperty("databaseIntegration")' in build


def test_the_isolated_target_is_enforced_before_any_context_starts():
    guard = ROOT / "backend/src/test/java/com/specagent/support/DatabaseTestContextGuard.java"
    assert guard.is_file(), "the database context guard must exist"
    text = guard.read_text(encoding="utf-8")
    assert "EvalLiveIsolatedDatabase" in text
    assert "verifyBeforeContext" in text
    assert (ROOT / "backend/src/test/resources/META-INF/spring.factories").is_file()


def test_zen_resolves_public_release_without_local_maven_or_source_override_in_ci():
    build = BUILD.read_text(encoding="utf-8")
    workflow = workflow_text()
    assert 'url = uri("https://jitpack.io")' in build
    assert "mavenLocal()" not in build
    assert 'implementation("com.sharedlibs:opencode-zen' not in build
    assert "OPENCODE_ZEN_LOCAL" not in workflow
    assert "dependencyInsight --dependency opencode-zen --configuration compileClasspath" in workflow
    assert "gradlew compileJava bootJar" in workflow
    assert "5434" not in workflow
    assert "spec_agent_test" not in workflow
    assert "DROP DATABASE" not in workflow
    assert "github.run_id" in workflow and "github.run_attempt" in workflow
    assert "SPRING_FLYWAY_URL: ${{ steps.isolated-db.outputs.url }}" in workflow
    assert "-PexternalServices=true" not in workflow


def test_database_task_cannot_silently_select_a_nonexistent_tag():
    build = BUILD.read_text(encoding="utf-8")
    selection = build.split('tasks.register<Test>("testDatabaseIntegration")', 1)[1].split('// Reject the whole graph', 1)[0]
    assert 'includeTestsMatching("*IntegrationTest")' in selection
    assert 'includeTags("database-integration")' not in selection
    assert 'excludeTestsMatching("com.specagent.eval.EvalLive*")' in selection
    assert 'Live provider tasks require explicit -PexternalServices=true' in build


def test_disabled_bootstrap_guards_are_executed_in_the_offline_scope():
    build = BUILD.read_text(encoding="utf-8")
    offline = build.split('val auditedOfflineTests = setOf(', 1)[1].split(')', 1)[0]
    assert '"com.specagent.support.DatabaseTestContextGuardTest"' in offline
    assert '"com.specagent.eval.P3BLiveDatabaseGuardTest"' in offline
    assert 'if (databaseIntegrationEnabled.get() && name != "testOffline")' in build
    assert 'excludeTestsMatching("com.specagent.eval.P3BLiveDatabaseGuardTest")' in build
    assert 'systemProperty("specagent.database-tests.enabled", "false")' in build
