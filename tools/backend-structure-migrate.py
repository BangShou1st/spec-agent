#!/usr/bin/env python3
"""Backend structure migration (2026-09, branch codex/backend-structure-refactor).

Moves packages/classes to their final business-owned locations and rewrites
all Java references (package decls, imports, javadoc FQNs, string FQNs).

Re-runnable: each class move lists candidate source packages (the original
location plus intermediate locations from earlier passes); when the file is
already at its target the rule is a no-op.

Usage (from repo root):
  python tools/backend-structure-migrate.py            # apply
  python tools/backend-structure-migrate.py --dry-run  # print plan only

The companion semantic edits (not expressible as file moves) are recorded in
the branch commit messages and docs/BACKEND_STRUCTURE.md:
  - SkillProperties now owns its git-proxy default constant (config no longer
    imports the importing implementation).
  - agent-brain protocol.py doc comment renamed with agent.protocol.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "backend", "src")

# ------------------------------------------------------------- class moves
# Each entry: (candidate_source_packages, target_package, simple_name)
CLASS_MOVES = [
    # agent root: model interaction vocabulary -> decision
    (["com.specagent.agent"], "com.specagent.agent.decision", "AgentAction"),
    (["com.specagent.agent"], "com.specagent.agent.decision", "ModelRequest"),
    (["com.specagent.agent"], "com.specagent.agent.decision", "ModelResponse"),
    (["com.specagent.agent"], "com.specagent.agent.decision", "ModelResponseCorrelation"),
    (["com.specagent.agent", "com.specagent.agent.decision"], "com.specagent.agent.protocol", "ModelContractException"),
    # agent root: task vocabulary lives with the model request seam
    (["com.specagent.agent", "com.specagent.agent.runtime"], "com.specagent.agent.decision", "AgentTaskType"),
    # agent root: run pipeline -> runtime
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AgentRun"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AgentRunService"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AgentRunRepository"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AgentRunStatus"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AgentRunFailureService"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AgentRunTerminalizationService"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AgentRunRequestFingerprint"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "AnswerRunResult"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "ReplacementRunResult"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "SpecRunResult"),
    (["com.specagent.agent"], "com.specagent.agent.runtime", "IdempotencyKeyReusedException"),
    # brain link config is broker-owned (decision engines and broker read it)
    (["com.specagent.agent.runtime"], "com.specagent.agent.broker", "AgentBrainProperties"),
    # application.agent split: orchestration -> runtime, HTTP views -> runtime (controllers import them)
    (["com.specagent.application.agent"], "com.specagent.agent.runtime", "AnswerCycleRunCommandService"),
    (["com.specagent.application.agent", "com.specagent.agent.api"], "com.specagent.agent.runtime", "CreateRunRequest"),
    (["com.specagent.application.agent", "com.specagent.agent.api"], "com.specagent.agent.runtime", "AcceptedRunView"),
    (["com.specagent.application.agent", "com.specagent.agent.api"], "com.specagent.agent.runtime", "AgentRunViewResponse"),
    # eligibility result types travel in the request envelope -> protocol
    (["com.specagent.agent.eligibility", "com.specagent.agent.action"], "com.specagent.agent.protocol", "ActionEligibility"),
    (["com.specagent.agent.eligibility", "com.specagent.agent.action"], "com.specagent.agent.protocol", "ActionEligibilityConstraint"),
    (["com.specagent.agent.eligibility", "com.specagent.agent.action"], "com.specagent.agent.protocol", "ActionEligibilityReasonCode"),
    # model.inference split: neutral seam -> contract, implementations -> provider
    (["com.specagent.model.inference"], "com.specagent.model.contract", "ModelInferenceGateway"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "ModelInferenceRequest"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "ModelInferenceResponse"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "ModelInferenceMessage"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "ModelOutputContract"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "ActiveProviderPort"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "CustomRuntimeSettingsPort"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "OpenCodeRuntimeSettingsPort"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "OpenRouterRuntimeSettingsPort"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "RuntimeCustomSettings"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "RuntimeOpenCodeSettings"),
    (["com.specagent.model.inference"], "com.specagent.model.contract", "RuntimeOpenRouterSettings"),
    (["com.specagent.model.inference"], "com.specagent.model.provider", "CustomInferenceGateway"),
    (["com.specagent.model.inference"], "com.specagent.model.provider", "OpenCodeModelInferenceGateway"),
    (["com.specagent.model.inference"], "com.specagent.model.provider", "OpenRouterInferenceGateway"),
    (["com.specagent.model.inference"], "com.specagent.model.provider", "RoutingModelInferenceGateway"),
    # neutral provider identity + streaming callbacks consumed through the seam
    (["com.specagent.model.provider"], "com.specagent.model.contract", "ModelProvider"),
    (["com.specagent.model.provider"], "com.specagent.model.contract", "FragmentListener"),
    (["com.specagent.model.provider"], "com.specagent.model.contract", "StreamCancelledException"),
    # retrieval: the embedding port is shared vocabulary (breaks persistence<->embedding)
    (["com.specagent.retrieval.embedding"], "com.specagent.retrieval", "EmbeddingGateway"),
    # assistant: the brain input value object is consumed by the model layer
    (["com.specagent.globalassistant.context"], "com.specagent.assistant.model", "GlobalAssistantContext"),
    # assistant: run dispatch / handoff orchestration is runtime, not conversation
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "RunDispatcher"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "TurnHandoffListener"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "TurnHandoffService"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "RunTerminalEvent"),
    # assistant: pending-turn errors and conversation summarization are conversation-scoped
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.conversation", "SteerPendingException"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.conversation", "SteerRejectedException"),
    # shared assistant error-code vocabulary (used by runtime and tool capabilities)
    (["com.specagent.globalassistant.runtime", "com.specagent.assistant.runtime"], "com.specagent.assistant", "GlobalAssistantErrorCode"),
    # workspace: the shared command-execution kernel belongs to the route command hub
    (["com.specagent.application.support", "com.specagent.workspace"], "com.specagent.workspace.route", "CommandExecution"),
    # agent: fail-closed eligibility rejection extends the wire contract exception
    (["com.specagent.agent.action", "com.specagent.agent.protocol"], "com.specagent.agent.protocol", "ActionIneligibleException"),
    # agent: acceptance is run-flow orchestration; policy stays pure evaluation
    (["com.specagent.agent.policy", "com.specagent.agent.runtime"], "com.specagent.agent.runtime", "ProposalAcceptanceService"),
    # agent: stale-context projection checking is snapshot responsibility
    (["com.specagent.agent.action", "com.specagent.agent.snapshot"], "com.specagent.agent.snapshot", "StaleContextChecker"),
    # assistant: summarization is orchestrated by the runtime (drives the brain)
    (["com.specagent.globalassistant.model", "com.specagent.assistant.model", "com.specagent.assistant.conversation"], "com.specagent.assistant.runtime", "GlobalAssistantSummaryService"),
]

# ----------------------------------------------------------- package moves
PACKAGE_MOVES = [
    # -- workspace group -----------------------------------------------------
    ("com.specagent.project", "com.specagent.workspace.project"),
    ("com.specagent.api.project", "com.specagent.workspace.project"),
    ("com.specagent.application.project", "com.specagent.workspace.project"),
    ("com.specagent.route", "com.specagent.workspace.route"),
    ("com.specagent.api.route", "com.specagent.workspace.route"),
    ("com.specagent.application.route", "com.specagent.workspace.route"),
    ("com.specagent.readmodel.route", "com.specagent.workspace.route"),
    ("com.specagent.readmodel.lineage", "com.specagent.workspace.route"),
    ("com.specagent.node", "com.specagent.workspace.node"),
    ("com.specagent.api.node", "com.specagent.workspace.node"),
    ("com.specagent.application.node", "com.specagent.workspace.node"),
    ("com.specagent.answer", "com.specagent.workspace.answer"),
    ("com.specagent.context", "com.specagent.workspace.context"),
    ("com.specagent.patch", "com.specagent.workspace.patch"),
    ("com.specagent.graph", "com.specagent.workspace.graph"),
    ("com.specagent.readmodel.graph", "com.specagent.workspace.graph"),
    ("com.specagent.api.graph", "com.specagent.workspace.graph"),
    ("com.specagent.spec", "com.specagent.workspace.spec"),
    ("com.specagent.api.spec", "com.specagent.workspace.spec"),
    ("com.specagent.api.requirement", "com.specagent.workspace.spec"),
    ("com.specagent.readmodel.requirement", "com.specagent.workspace.spec"),
    ("com.specagent.profile", "com.specagent.workspace.profile"),
    ("com.specagent.application.support", "com.specagent.workspace"),
    ("com.specagent.api.common", "com.specagent.web"),
    # -- agent group ----------------------------------------------------------
    ("com.specagent.agent.contract", "com.specagent.agent.protocol"),
    ("com.specagent.agent.contracts", "com.specagent.agent.decision"),
    ("com.specagent.agent.gates", "com.specagent.agent.decision"),
    ("com.specagent.agent.eligibility", "com.specagent.agent.action"),
    ("com.specagent.agent.loop", "com.specagent.agent.runtime"),
    ("com.specagent.api.agent", "com.specagent.agent.api"),
    # -- model / modelsettings ------------------------------------------------
    ("com.specagent.model.gateway", "com.specagent.model.contract"),
    ("com.specagent.settings.custom", "com.specagent.modelsettings"),
    ("com.specagent.settings.opencode", "com.specagent.modelsettings"),
    ("com.specagent.settings.openrouter", "com.specagent.modelsettings"),
    ("com.specagent.settings.provider", "com.specagent.modelsettings"),
    ("com.specagent.settings", "com.specagent.modelsettings"),
    # intermediate states from the first pass: flatten the config domains
    ("com.specagent.modelsettings.custom", "com.specagent.modelsettings"),
    ("com.specagent.modelsettings.opencode", "com.specagent.modelsettings"),
    ("com.specagent.modelsettings.openrouter", "com.specagent.modelsettings"),
    ("com.specagent.modelsettings.provider", "com.specagent.modelsettings"),
    # -- assistant ------------------------------------------------------------
    ("com.specagent.globalassistant.api", "com.specagent.assistant.api"),
    ("com.specagent.globalassistant.application", "com.specagent.assistant.runtime"),
    ("com.specagent.globalassistant.runtime", "com.specagent.assistant.runtime"),
    ("com.specagent.globalassistant.stream", "com.specagent.assistant.runtime"),
    ("com.specagent.globalassistant.model", "com.specagent.assistant.model"),
    ("com.specagent.globalassistant.conversation", "com.specagent.assistant.conversation"),
    ("com.specagent.globalassistant.context", "com.specagent.assistant.runtime"),
    ("com.specagent.globalassistant.turn", "com.specagent.assistant.conversation"),
    ("com.specagent.globalassistant.tool", "com.specagent.assistant.tool"),
    ("com.specagent.globalassistant", "com.specagent.assistant"),
    # -- skill / connection / mcp / retrieval ---------------------------------
    ("com.specagent.retrieval.api", "com.specagent.retrieval"),
    ("com.specagent.connection.domain", "com.specagent.connection"),
    ("com.specagent.connection.service", "com.specagent.connection"),
    ("com.specagent.connection.persistence", "com.specagent.connection"),
    ("com.specagent.api.connection", "com.specagent.connection"),
    ("com.specagent.api.skill", "com.specagent.skill"),
    ("com.specagent.mcp.config", "com.specagent.mcp"),
    ("com.specagent.mcp.persistence", "com.specagent.mcp"),
    # -- api/settings ----------------------------------------------------------
    ("com.specagent.api.settings", "com.specagent.modelsettings"),
]

WORD = r"(?![A-Za-z0-9_])"


def iter_java_files():
    for dirpath, _dirs, files in os.walk(SRC):
        for fn in files:
            if fn.endswith(".java"):
                yield os.path.join(dirpath, fn)


def build_rewrite_rules():
    class_rules = []
    for candidates, new_pkg, cls in CLASS_MOVES:
        for old_pkg in candidates:
            class_rules.append(
                (re.compile(re.escape(old_pkg + "." + cls) + WORD), new_pkg + "." + cls))
    package_rules = []
    for old_pkg, new_pkg in PACKAGE_MOVES:
        package_rules.append((re.compile(re.escape(old_pkg) + WORD), new_pkg))
    class_rules.sort(key=lambda r: -len(r[0].pattern))
    package_rules.sort(key=lambda r: -len(r[0].pattern))
    return class_rules, package_rules


def rewrite_text(text, class_rules, package_rules):
    for pattern, repl in class_rules:
        text = pattern.sub(repl, text)
    for pattern, repl in package_rules:
        text = pattern.sub(repl, text)
    return text


def find_file(pkg_candidates, cls):
    """Locate <cls>.java under any candidate package (main or test)."""
    for sub in ("main", "test"):
        for pkg in pkg_candidates:
            p = os.path.join(SRC, sub, "java", *pkg.split("."), cls + ".java")
            if os.path.exists(p):
                return p
    # fallback: search the whole tree (unique class names)
    for path in iter_java_files():
        if os.path.basename(path) == cls + ".java":
            rel = os.path.relpath(path, SRC).replace("\\", "/").split("/")
            if rel[1] == "java":
                return path
    return None


def move_file(src, dst_dir, dry):
    os.makedirs(dst_dir, exist_ok=True)
    dst = os.path.join(dst_dir, os.path.basename(src))
    if os.path.abspath(src) == os.path.abspath(dst):
        return None
    if dry:
        print(f"MOVE {os.path.relpath(src, ROOT)} -> {os.path.relpath(dst, ROOT)}")
        return dst
    if os.path.exists(dst):
        return None  # already moved
    shutil.move(src, dst)
    return dst


def normalize_pkg(dst):
    rel = os.path.relpath(dst, SRC).replace("\\", "/")
    parts = rel.split("/")
    return ".".join(parts[2:-1])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    dry = args.dry_run
    moved = 0

    class_rules, package_rules = build_rewrite_rules()

    # Phase A: rewrite references everywhere.
    for path in list(iter_java_files()):
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        new_text = rewrite_text(text, class_rules, package_rules)
        if new_text != text:
            if dry:
                print(f"REWRITE {os.path.relpath(path, ROOT)}")
            else:
                with open(path, "w", encoding="utf-8", newline="") as fh:
                    fh.write(new_text)

    # Phase B: move class files (skip when already at target).
    for candidates, new_pkg, cls in CLASS_MOVES:
        target_pkg = normalize_pkg(os.path.join(SRC, "main", "java", *new_pkg.split("."), cls + ".java"))
        src = find_file(candidates, cls)
        if src is None:
            continue
        cur_pkg = normalize_pkg(src)
        if cur_pkg == target_pkg:
            continue
        sub = "main" if os.sep + "main" + os.sep in src or "/main/" in src.replace("\\", "/") else "test"
        dst_dir = os.path.join(SRC, sub, "java", *new_pkg.split("."))
        dst = move_file(src, dst_dir, dry)
        if dst:
            moved += 1
            if not dry:
                with open(dst, encoding="utf-8") as fh:
                    text = fh.read()
                text = re.sub(r"^package [\w.]+;", f"package {target_pkg};", text, count=1, flags=re.M)
                with open(dst, "w", encoding="utf-8", newline="") as fh:
                    fh.write(text)

    # Phase C: move whole packages (files remaining under the old root).
    for old_pkg, new_pkg in PACKAGE_MOVES:
        for sub in ("main", "test"):
            src_dir = os.path.join(SRC, sub, "java", *old_pkg.split("."))
            if not os.path.isdir(src_dir):
                continue
            target_root = os.path.join(SRC, sub, "java", *new_pkg.split("."))
            for fn in sorted(os.listdir(src_dir)):
                if not fn.endswith(".java"):
                    continue
                src = os.path.join(src_dir, fn)
                cur_pkg = normalize_pkg(src)
                if cur_pkg == new_pkg:
                    continue
                dst = move_file(src, target_root, dry)
                if dst:
                    moved += 1
                    if not dry:
                        with open(dst, encoding="utf-8") as fh:
                            text = fh.read()
                        text = re.sub(r"^package [\w.]+;", f"package {new_pkg};", text, count=1, flags=re.M)
                        with open(dst, "w", encoding="utf-8", newline="") as fh:
                            fh.write(text)

    if not dry:
        for dirpath, dirnames, files in os.walk(SRC, topdown=False):
            if not os.listdir(dirpath):
                os.rmdir(dirpath)
        bad = 0
        for path in iter_java_files():
            declared = normalize_pkg(path)
            with open(path, encoding="utf-8") as fh:
                m = re.search(r"^package ([\w.]+);", fh.read(), re.M)
            if not m or m.group(1) != declared:
                print(f"INCONSISTENT: {path}: declared={m.group(1) if m else None} actual={declared}", file=sys.stderr)
                bad += 1
        print(f"moved {moved} files; inconsistent packages: {bad}")
    else:
        print(f"dry-run: would move {moved} files")


if __name__ == "__main__":
    main()
