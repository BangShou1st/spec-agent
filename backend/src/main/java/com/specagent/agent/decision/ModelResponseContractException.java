package com.specagent.agent.decision;

import com.specagent.agent.protocol.AgentContractException;

/** A Brain response failed a verified Java wire or response validator boundary. */
public final class ModelResponseContractException extends AgentContractException {

    private final ModelContractDiagnostic diagnostic;

    public ModelResponseContractException(ModelContractDiagnostic diagnostic) {
        super(safeMessage(diagnostic));
        this.diagnostic = diagnostic;
    }

    public ModelContractDiagnostic diagnostic() {
        return diagnostic;
    }

    private static String safeMessage(ModelContractDiagnostic diagnostic) {
        if (diagnostic == null) return "Model response contract failed (diagnostic unavailable)";
        return "Model response contract failed at " + diagnostic.fieldPath()
                + " (" + diagnostic.machineCode() + ", diagnosticId="
                + diagnostic.diagnosticId() + ")";
    }
}
