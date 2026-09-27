package com.specagent.agent.action;

import com.specagent.agent.protocol.ActionIneligibleException;

import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.protocol.ActionEligibility;
import com.specagent.agent.protocol.ActionEligibilityReasonCode;

import com.specagent.agent.protocol.ActionProposal;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文件名:ActionEligibilityGate.java
 *
 * 用途:动作资格评估的 Runtime 编排入口,支持 shadow(影子)与
 * enforced(强制)两种模式。shadow 模式只记录准确的裁决结果,
 * 绝不改变提案或执行路径;enforced 模式则会在不合格时真正否决。
 *
 * 协作:决策请求经 prepareDecisionRequest 附加资格信息后发给模型;
 * Brain 响应的消费方调用 assess 得到裁决,再调用 enforce 执行否决。
 */
@Service
public class ActionEligibilityGate {

    public enum Mode {
        SHADOW,
        ENFORCED;

        static Mode parse(String value) {
            return value == null ? SHADOW : valueOf(value.strip().toUpperCase());
        }
    }

    private final ActionEligibilityEvaluator evaluator;
    private final ActionEligibilityValidator validator;
    private final Mode mode;

    @Autowired
    public ActionEligibilityGate(
            @Value("${spec.agent.action-eligibility.mode:shadow}") String mode) {
        this(new ActionEligibilityEvaluator(), new ActionEligibilityValidator(), Mode.parse(mode));
    }

    ActionEligibilityGate(ActionEligibilityEvaluator evaluator,
                          ActionEligibilityValidator validator,
                          Mode mode) {
        this.evaluator = evaluator;
        this.validator = validator;
        this.mode = mode;
    }

    /**
     * shadow 模式的请求保持字节级兼容的 V2;enforced 模式发送 V3,
     * 并把 Runtime 持有的资格掩码暴露给模型。
     */
    public AgentRequestEnvelope prepareDecisionRequest(AgentRequestEnvelope base) {
        if (mode == Mode.SHADOW) {
            return base;
        }
        ActionEligibility eligibility = evaluator.evaluate(base);
        return new AgentRequestEnvelope(
                AgentProtocol.INPUT_PROTOCOL_VERSION_V3,
                base.runId(), base.event(), base.snapshot(), base.capabilities(),
                base.decisionBudget(), eligibility);
    }

    public Assessment assess(AgentRequestEnvelope request, ActionProposal proposal) {
        ActionEligibility computed = evaluator.evaluate(request);
        if (request.actionEligibility() != null
                && !computed.basisHash().equals(request.actionEligibility().basisHash())) {
            return new Assessment(mode, computed, proposal.actionFamily(), false, true,
                    List.of(ActionEligibilityReasonCode.ELIGIBILITY_BASIS_MISMATCH));
        }
        try {
            validator.validateSelection(request, proposal, computed);
            return new Assessment(mode, computed, proposal.actionFamily(), true, false, List.of());
        } catch (ActionIneligibleException ex) {
            return new Assessment(mode, computed, proposal.actionFamily(),
                    computed.eligibleFamilies().contains(proposal.actionFamily()), true,
                    List.of(ex.reasonCode()));
        }
    }

    public void enforce(Assessment assessment) {
        if (mode == Mode.ENFORCED && assessment.wouldVeto()) {
            throw new ActionIneligibleException(assessment.reasonCodes().get(0));
        }
    }

    public record Assessment(Mode mode,
                             ActionEligibility eligibility,
                             String selectedAction,
                             boolean selectedActionEligible,
                             boolean wouldVeto,
                             List<ActionEligibilityReasonCode> reasonCodes) {
        public Assessment {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }
}
