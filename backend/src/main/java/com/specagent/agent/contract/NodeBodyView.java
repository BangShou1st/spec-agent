package com.specagent.agent.contract;

import java.util.List;

/**
 * Generic node body in Graph language. The current V1 question workflow is
 * projected into this shape; workflow names never enter the contract.
 */
public record NodeBodyView(String text, List<OptionView> options, boolean acceptsFreeText) {

    public NodeBodyView {
        options = options == null ? List.of() : List.copyOf(options);
    }
}
