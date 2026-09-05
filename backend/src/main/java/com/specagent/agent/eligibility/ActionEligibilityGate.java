package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runtime orchestration for eligibility shadow/enforcement modes. Shadow mode
 * records the exact verdict but never changes the proposal or execution path.
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
     * Shadow requests remain byte-compatible V2. Enforcement sends V3 and
     * exposes the Runtime-owned mask to the model.
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
