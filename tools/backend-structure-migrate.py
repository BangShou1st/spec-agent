#!/usr/bin/env python3
"""文件名:backend-structure-migrate.py

后端结构迁移脚本(2026-09,codex/backend-structure-refactor 分支)。

把各个包/类移动到最终的业务归属位置,并同步重写全部 Java 引用
(package 声明、import 语句、javadoc 与字符串中的全限定名)。

可重复执行:每条类迁移规则都列出候选来源包(原始位置加上此前各轮迁移的
中间位置);当文件已在目标位置时,该规则就是空操作。

用法(在仓库根目录执行):
  python tools/backend-structure-migrate.py            # 执行迁移
  python tools/backend-structure-migrate.py --dry-run  # 只打印计划,不改动

无法用"文件移动"表达的配套语义修改,记录在分支提交说明和
docs/BACKEND_STRUCTURE.md 中:
  - SkillProperties 现在自己持有 git-proxy 默认常量(config 不再依赖
    引入方实现)。
  - agent-brain protocol.py 的文档注释随 agent.protocol 重命名。

注意:com.specagent.trace 属于生产代码(SemanticTraceRecorder 实现了启动时
消费的运行时 AgentTracePort),因此保留在 main 中——移动到 agent.trace,
而不是移入 eval 源集。
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "backend", "src")

# ------------------------------------------------------------- 类迁移
# 每条记录:(候选来源包列表, 目标包, 类名)
CLASS_MOVES = [
    # agent 根包:模型交互词汇 -> decision
    (["com.specagent.agent"], "com.specagent.agent.decision", "AgentAction"),
    (["com.specagent.agent"], "com.specagent.agent.decision", "ModelRequest"),
    (["com.specagent.agent"], "com.specagent.agent.decision", "ModelResponse"),
    (["com.specagent.agent"], "com.specagent.agent.decision", "ModelResponseCorrelation"),
    (["com.specagent.agent", "com.specagent.agent.decision"], "com.specagent.agent.protocol", "ModelContractException"),
    # agent 根包:任务词汇与模型请求接缝放在一起
    (["com.specagent.agent", "com.specagent.agent.runtime"], "com.specagent.agent.decision", "AgentTaskType"),
    # agent 根包:运行流水线 -> runtime
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
    # brain 链路配置归 broker 所有(决策引擎和 broker 都会读取)
    (["com.specagent.agent.runtime"], "com.specagent.agent.broker", "AgentBrainProperties"),
    # application.agent 拆分:编排 -> runtime,HTTP 视图 -> runtime(controller 会引用它们)
    (["com.specagent.application.agent"], "com.specagent.agent.runtime", "AnswerCycleRunCommandService"),
    (["com.specagent.application.agent", "com.specagent.agent.api"], "com.specagent.agent.runtime", "CreateRunRequest"),
    (["com.specagent.application.agent", "com.specagent.agent.api"], "com.specagent.agent.runtime", "AcceptedRunView"),
    (["com.specagent.application.agent", "com.specagent.agent.api"], "com.specagent.agent.runtime", "AgentRunViewResponse"),
    # 资格判定结果类型随请求信封传递 -> protocol
    (["com.specagent.agent.eligibility", "com.specagent.agent.action"], "com.specagent.agent.protocol", "ActionEligibility"),
    (["com.specagent.agent.eligibility", "com.specagent.agent.action"], "com.specagent.agent.protocol", "ActionEligibilityConstraint"),
    (["com.specagent.agent.eligibility", "com.specagent.agent.action"], "com.specagent.agent.protocol", "ActionEligibilityReasonCode"),
    # model.inference 拆分:中立接缝 -> contract,具体实现 -> provider
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
    # 中立的供应商标识与流式回调,通过接缝消费
    (["com.specagent.model.provider"], "com.specagent.model.contract", "ModelProvider"),
    (["com.specagent.model.provider"], "com.specagent.model.contract", "FragmentListener"),
    (["com.specagent.model.provider"], "com.specagent.model.contract", "StreamCancelledException"),
    # retrieval:embedding 端口是共享词汇(打破 persistence 与 embedding 的耦合)
    (["com.specagent.retrieval.embedding"], "com.specagent.retrieval", "EmbeddingGateway"),
    # assistant:brain 输入值对象由模型层消费
    (["com.specagent.globalassistant.context"], "com.specagent.assistant.model", "GlobalAssistantContext"),
    # assistant:运行分发/交接编排属于 runtime,不属于会话
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "RunDispatcher"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "TurnHandoffListener"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "TurnHandoffService"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.runtime", "RunTerminalEvent"),
    # assistant:待处理轮次的异常与会话摘要属于会话域
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.conversation", "SteerPendingException"),
    (["com.specagent.globalassistant.turn", "com.specagent.assistant.runtime"], "com.specagent.assistant.conversation", "SteerRejectedException"),
    # assistant 共享的错误码词汇(runtime 和工具能力都会使用)
    (["com.specagent.globalassistant.runtime", "com.specagent.assistant.runtime"], "com.specagent.assistant", "GlobalAssistantErrorCode"),
    # workspace:共享的命令执行内核归属 route 命令中枢
    (["com.specagent.application.support", "com.specagent.workspace"], "com.specagent.workspace.route", "CommandExecution"),
    # agent:fail-closed 的资格拒绝异常继承线上契约异常
    (["com.specagent.agent.action", "com.specagent.agent.protocol"], "com.specagent.agent.protocol", "ActionIneligibleException"),
    # agent:接受操作是运行流程编排;policy 保持纯评估
    (["com.specagent.agent.policy", "com.specagent.agent.runtime"], "com.specagent.agent.runtime", "ProposalAcceptanceService"),
    # agent:过期上下文投影检查是 snapshot 的职责
    (["com.specagent.agent.action", "com.specagent.agent.snapshot"], "com.specagent.agent.snapshot", "StaleContextChecker"),
    # assistant:摘要由 runtime 编排(驱动 brain)
    (["com.specagent.globalassistant.model", "com.specagent.assistant.model", "com.specagent.assistant.conversation"], "com.specagent.assistant.runtime", "GlobalAssistantSummaryService"),
]

# ----------------------------------------------------------- 包迁移
PACKAGE_MOVES = [
    # -- workspace 分组 -----------------------------------------------------
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
    # -- agent 分组 ----------------------------------------------------------
    # 注意:agent.gates 刻意保持独立包——把 gates 并入 agent.decision 会
    # 破坏"决策层不接触持久化"的边界(ContextGuard/SpecSourceReferenceGuard
    # 需要读取仓库)。只有模型输出词汇(agent.contracts)并入 decision。
    ("com.specagent.agent.contract", "com.specagent.agent.protocol"),
    ("com.specagent.agent.contracts", "com.specagent.agent.decision"),
    ("com.specagent.agent.eligibility", "com.specagent.agent.action"),
    ("com.specagent.agent.loop", "com.specagent.agent.runtime"),
    ("com.specagent.api.agent", "com.specagent.agent.api"),
    # -- model / modelsettings 分组 ------------------------------------------
    ("com.specagent.model.gateway", "com.specagent.model.contract"),
    ("com.specagent.settings.custom", "com.specagent.modelsettings"),
    ("com.specagent.settings.opencode", "com.specagent.modelsettings"),
    ("com.specagent.settings.openrouter", "com.specagent.modelsettings"),
    ("com.specagent.settings.provider", "com.specagent.modelsettings"),
    ("com.specagent.settings", "com.specagent.modelsettings"),
    # 第一轮迁移留下的中间状态:把各配置子域拍平
    ("com.specagent.modelsettings.custom", "com.specagent.modelsettings"),
    ("com.specagent.modelsettings.opencode", "com.specagent.modelsettings"),
    ("com.specagent.modelsettings.openrouter", "com.specagent.modelsettings"),
    ("com.specagent.modelsettings.provider", "com.specagent.modelsettings"),
    # -- assistant 分组 -------------------------------------------------------
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
    # -- skill / connection / mcp / retrieval 分组 -----------------------------
    ("com.specagent.retrieval.api", "com.specagent.retrieval"),
    ("com.specagent.connection.domain", "com.specagent.connection"),
    ("com.specagent.connection.service", "com.specagent.connection"),
    ("com.specagent.connection.persistence", "com.specagent.connection"),
    ("com.specagent.api.connection", "com.specagent.connection"),
    ("com.specagent.api.skill", "com.specagent.skill"),
    ("com.specagent.mcp.config", "com.specagent.mcp"),
    ("com.specagent.mcp.persistence", "com.specagent.mcp"),
    # -- api/settings 分组 ------------------------------------------------------
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
    """在任一候选包下定位 <cls>.java(main 或 test 源集)。"""
    for sub in ("main", "test"):
        for pkg in pkg_candidates:
            p = os.path.join(SRC, sub, "java", *pkg.split("."), cls + ".java")
            if os.path.exists(p):
                return p
    # 兜底:全树搜索(类名唯一时可用)
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
        return None  # 已经移动过
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

    # 阶段 A:全局重写所有引用。
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

    # 阶段 B:移动类文件(已在目标位置时跳过)。
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

    # 阶段 C:移动整包(残留在旧根目录下的文件)。
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
