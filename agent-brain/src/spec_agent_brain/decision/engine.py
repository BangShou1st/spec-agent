"""文件名:engine.py

用途:DECISION 引擎——用一次模型调用完成 reflection + planning,再组装出
由 runtime 盖章的 action proposal 信封。

Brain 使用自己收到的可信请求来填写 base context 标识(绝不采信模型输出),
并在本地预先校验 source refs 是否在快照允许范围内;Java 侧随后仍会对全部
内容做 fail-closed 复核。
"""

import json
import logging
import uuid
from typing import Any

from ..diagnostics import semantic_diagnostics
from ..contracts.decisions import (
    ActionProposal,
    AgentV2ResponseEnvelope,
    AgentV3ResponseEnvelope,
    ModelDecisionOutput,
    ObservationView,
    UsageView,
)
from ..contracts.inputs import AgentV2RequestEnvelope, AgentV3RequestEnvelope
from ..contracts.protocol import DECISION_PROTOCOL_VERSION, DECISION_PROTOCOL_VERSION_V3
from ..model_client import ChatMessage, ModelClient
from ..prompts import decision as decision_prompt


logger = logging.getLogger("spec_agent_brain")

# 解析层唯一需要归一化的、已记录在案的模型偏差:模型偶尔会把该字段从
# ``action`` 内部提升到自己 JSON 对象的顶层,而契约里顶层并没有这个字段。
TOP_LEVEL_SOURCE_REFS = "sourceRefs"


class BrainContractError(RuntimeError):
    """模型输出违反 brain 自身输出契约时抛出。"""


class UngroundedReferenceError(BrainContractError):
    """输出引用了冻结快照允许范围之外的 ref。

    单独设一个类型是因为 Runtime 要分别上报:越界引用是路由/分支 grounding
    门禁在正常工作,而不是模型响应损坏;把两者混成一个不可分辨的失败,
    会掩盖到底是哪一侧出了问题。
    """


class AmbiguousSourceRefsError(BrainContractError):
    """输出携带了两份*不同*的 sourceRefs 列表。

    解析层只归并可证明等价的形态。两份不同的列表没有定义好的合并语义,
    所以直接拒绝,而不是悄悄覆盖或取并集。
    """


class ConflictSurfacingError(BrainContractError):
    """存在未解决的冲突,但输出遗漏了 observation.conflicts。

    与其他契约失败区分开,因为它是唯一能通过第二次、带明确指令的调用真正
    修复的违规:未解决的 claim 仍留在快照里,如果不做修复,该路由上后续
    每次决策都会以同样的方式失败,项目将永远无法继续。
    """


class ActionIneligibleBrainError(BrainContractError):
    """模型选择了 Runtime 持有的 V3 掩码之外的 action family。"""


CONFLICT_REPAIR_INSTRUCTION = (
    "上一次输出违反了输出契约：snapshot.effectiveClaims 中存在 kind=conflict 且 "
    "status=unresolved 的 claim，但 observation.conflicts 为空。未解决冲突如下：\n"
    "{conflicts}\n"
    "请重新输出完整的决策 JSON（保持 JSON 格式与全部必需字段），"
    "并在 observation.conflicts 中至少逐条列出上述冲突，"
    "同时按规则 9 让本周期的主动作直接推进冲突解决。只输出 JSON。"
)


def handle_decision(
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope,
        client: ModelClient) -> AgentV2ResponseEnvelope | AgentV3ResponseEnvelope:
    if request.decision_budget.max_model_calls < 1:
        raise BrainContractError("decision budget does not allow any model call")

    user_prompt = _render_model_input(request)
    messages = [
        ChatMessage(role="system", content=decision_prompt.SYSTEM_PROMPT),
        ChatMessage(role="user", content=user_prompt),
    ]
    completion = client.complete(
        run_id=str(request.run_id),
        call_type="DECISION",
        messages=messages,
    )
    model_calls = 1
    try:
        output = _validate_output(completion.content, request)
    except ConflictSurfacingError:
        # 只做一次有界的修复,且仅当 Runtime 声明的预算付得起第二次调用。
        # 其余一律不重试:其他任何违规仍然和以前一样在第一次输出上直接
        # fail-closed。
        if request.decision_budget.max_model_calls < 2:
            raise
        repair = CONFLICT_REPAIR_INSTRUCTION.format(
            conflicts="\n".join("- " + text for text in _unresolved_conflict_texts(request)))
        completion = client.complete(
            run_id=str(request.run_id),
            call_type="DECISION",
            messages=messages + [ChatMessage(role="user", content=repair)],
        )
        model_calls = 2
        output = _validate_output(completion.content, request)

    response_type = (AgentV3ResponseEnvelope
                     if isinstance(request, AgentV3RequestEnvelope)
                     else AgentV2ResponseEnvelope)
    response_version = (DECISION_PROTOCOL_VERSION_V3
                        if isinstance(request, AgentV3RequestEnvelope)
                        else DECISION_PROTOCOL_VERSION)

    response_values = dict(
        protocol_version=response_version,
        run_id=request.run_id,
        observation=output.observation,
        action_proposal=ActionProposal(
            action_family=output.action.action_family,
            payload=output.action.payload,
            # Runtime 持有的标识来自可信请求盖章,所以模型永远无法伪造或
            # 盖上过期的 base context。
            base_context_snapshot_id=request.snapshot.snapshot_id,
            base_context_hash=request.snapshot.context_hash,
            source_refs=output.action.source_refs,
            # proposalId 是 runtime 持有的 UUID;idempotencyKey 从可信的 run
            # 标识派生(每个 decision 周期一个 proposal)。
            proposal_id=uuid.uuid4(),
            idempotency_key=str(request.run_id),
            anchor_refs=output.action.anchor_refs,
        ),
        # 如实记账:修复调用会被上报,绝不隐藏。超过声明预算时 Java 会
        # 拒绝该响应。
        usage=UsageView(model_calls=model_calls, prompt_hashes=[]),
        diagnostics=semantic_diagnostics(
            decision_prompt.SYSTEM_PROMPT, user_prompt, "DECISION"),
    )
    if isinstance(request, AgentV3RequestEnvelope):
        response_values.update(
            selected_eligibility_version=request.action_eligibility.version,
            selected_eligibility_basis_hash=request.action_eligibility.basis_hash,
            eligibility_evidence_refs=output.action.source_refs,
        )
    return response_type(**response_values)


def _validate_output(
        content: str,
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope) -> ModelDecisionOutput:
    output = _parse_model_output(content)
    _check_source_refs(output, request)
    _check_conflict_action(output, request)
    if isinstance(request, AgentV3RequestEnvelope):
        _check_eligibility_action(output, request)
    return output


def _unresolved_conflict_texts(
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope) -> list[str]:
    return [claim.text for claim in request.snapshot.effective_claims
            if claim.kind == "conflict" and claim.status == "unresolved"]


def _render_model_input(
        request: AgentV2RequestEnvelope | AgentV3RequestEnvelope) -> str:
    """在不改动 Candidate C prompt 的前提下注入 V3 的 Runtime 控制数据。"""
    rendered = decision_prompt.render_user_prompt(request)
    if not isinstance(request, AgentV3RequestEnvelope):
        return rendered
    payload = json.loads(rendered)
    payload["actionEligibility"] = request.action_eligibility.model_dump(
        mode="json", by_alias=True)
    return json.dumps(payload, ensure_ascii=False)


def _parse_model_output(content: str) -> ModelDecisionOutput:
    try:
        raw = json.loads(content)
    except json.JSONDecodeError as exc:
        raise BrainContractError("model output is not valid JSON") from exc
    raw = _normalize_stray_source_refs(raw)
    try:
        return ModelDecisionOutput.model_validate(raw)
    except Exception as exc:  # pydantic ValidationError -> 转成有类型的 brain 失败
        raise BrainContractError(
            "model output violates the DECISION contract: "
            f"{exc} [{_output_layout(content)}]") from exc


def _normalize_stray_source_refs(raw: Any) -> Any:
    """针对唯一一个已记录在案的 DECISION 偏差做兼容垫片。

    模型偶尔会把 ``sourceRefs`` 作为 JSON 对象的*顶层*字段输出,而不是放在
    ``action`` 里。只有两种形态会被归一,且两者都可证明等价于模型使用了
    文档规定的布局:

    - ``action`` *缺少*该字段而顶层带一份合法 ref 列表:把值搬回
      ``action.sourceRefs``——该字段唯一被定义的位置;
    - 两处带的是*同一份*合法 ref 列表:顶层那份是纯重复,直接丢弃。

    用哪种形态只看键*是否存在*,绝不看值是否为真:已经定义了该字段的
    action 永远不会被当成"缺失"处理,所以一个存在但非法的值(``null``、
    ``false``、``0``、``""``、非字符串数组)永远不会被顶层副本修复。

    其余情况一律像以前一样 fail-closed:

    - 两份*不同*的合法列表——包括 action 里是空列表而顶层非空——没有定义
      的合并语义(覆盖、取并集、取更长的那个都不可接受),直接拒绝;
    - action 里的字段存在但不是合法 ref 列表时,原样保留,交给严格契约
      去拒绝;
    - 其他任何未知字段仍由严格契约拒绝,而且搬移过来的列表也不被信任——
      它仍要走完整的 schema、allowed-refs 白名单以及下游所有
      action/eligibility 检查。

    绝不发明、删除或改写任何 ref,也绝不剥离未知键。无法证明等价时,
    brain 持续拒绝。
    """
    if not isinstance(raw, dict) or TOP_LEVEL_SOURCE_REFS not in raw:
        return raw
    stray = raw[TOP_LEVEL_SOURCE_REFS]
    action = raw.get("action")
    if not _is_source_ref_list(stray) or not isinstance(action, dict):
        # 不是文档描述的形态:留给严格契约去拒绝。
        return raw
    if TOP_LEVEL_SOURCE_REFS in action:
        action_refs = action[TOP_LEVEL_SOURCE_REFS]
        if _is_source_ref_list(action_refs):
            if list(action_refs) == list(stray):
                raw.pop(TOP_LEVEL_SOURCE_REFS)
                logger.info(
                    "DECISION dropped duplicate top-level sourceRefs (%d refs)", len(stray))
                return raw
            raise AmbiguousSourceRefsError(
                "model output carries two different sourceRefs lists: "
                f"action={_bounded_refs(action_refs)} topLevel={_bounded_refs(stray)}")
        # action 定义了该字段,但值不是 ref 列表。这是非法值,不是缺失:
        # 绝不用顶层副本去修补——严格契约照旧拒绝它。
        return raw
    action[TOP_LEVEL_SOURCE_REFS] = list(stray)
    raw.pop(TOP_LEVEL_SOURCE_REFS)
    logger.info(
        "DECISION relocated top-level sourceRefs into action (%d refs)", len(stray))
    return raw


def _is_source_ref_list(value: Any) -> bool:
    """仅当值是字符串数组——即声明的 ``List[str]`` 形态——时为 True。"""
    return isinstance(value, list) and all(isinstance(item, str) for item in value)


def _output_layout(content: str) -> str:
    """被拒绝的 DECISION 输出的有界布局诊断。

    只记录键布局和两份 sourceRefs 列表——足以证明模型把字段放错了哪里,
    并能在离线复现这次拒绝——但绝不记录模型输出的其余部分,因此用户内容
    不会泄漏进日志。
    """
    try:
        raw = json.loads(content)
    except json.JSONDecodeError:
        return "layout=<not json>"
    if not isinstance(raw, dict):
        return f"layout=<{type(raw).__name__}>"
    action = raw.get("action")
    action_refs = action.get(TOP_LEVEL_SOURCE_REFS) if isinstance(action, dict) else None
    return ("layout=keys(" + ",".join(sorted(str(key) for key in raw.keys())) + ")"
            + " topLevelSourceRefs=" + _bounded_refs(raw.get(TOP_LEVEL_SOURCE_REFS))
            + " actionSourceRefs=" + _bounded_refs(action_refs))


# 诊断信息会渲染模型输出的 refs,而 refs 属于模型输出:同时限制条目数和
# 字符数,保证畸形的输出无法把无界的 payload 涌进日志行或有类型的错误
# detail 里。
REF_ENTRY_MAX_CHARS = 120
REF_RENDER_MAX_CHARS = 400


def _bounded_refs(value: Any) -> str:
    """有界地渲染一份 refs 值:最多五条,长度封顶。"""
    if value is None:
        return "<absent>"
    if not isinstance(value, list):
        return f"<{type(value).__name__}>"
    head = json.dumps(
        [_truncate(str(item), REF_ENTRY_MAX_CHARS) for item in value[:5]],
        ensure_ascii=False)
    if len(value) > 5:
        head = f"{head}+{len(value) - 5}"
    return _truncate(head, REF_RENDER_MAX_CHARS)


def _truncate(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[:limit] + "..."


def _check_source_refs(output: ModelDecisionOutput, request: AgentV2RequestEnvelope) -> None:
    allowed = set(request.snapshot.allowed_source_refs)
    for ref in output.action.source_refs:
        if ref not in allowed:
            raise UngroundedReferenceError(
                f"model referenced a source outside the allowed snapshot refs: {ref}")


def _check_eligibility_action(output: ModelDecisionOutput,
                              request: AgentV3RequestEnvelope) -> None:
    if output.action.action_family not in request.action_eligibility.eligible_families:
        raise ActionIneligibleBrainError(
            "model selected an action outside the Runtime eligibility mask")


def _check_conflict_action(output: ModelDecisionOutput,
                           request: AgentV2RequestEnvelope) -> None:
    """存在未解决的需求冲突却未被暴露时,fail-closed。

    NODE_QUERY 是只读的上下文对话,即使工作区有未解决的冲突也必须保持
    可用。对普通 planning 周期,冲突仍必须在 observation.conflicts 中如实
    暴露——但这里从不限制 ACTION 的选择(Slice 4):只读的能力调用和询问
    用户一样可接受。执行安全属于 Java Runtime 的 policy/stale/permission
    门禁,绝不属于这个契约检查。
    """
    if request.event.kind == "NODE_QUERY":
        return

    unresolved = [
        claim for claim in request.snapshot.effective_claims
        if claim.kind == "conflict" and claim.status == "unresolved"
    ]
    if not unresolved:
        return

    if not output.observation.conflicts:
        raise ConflictSurfacingError(
            "unresolved conflict requires a non-empty observation.conflicts")
