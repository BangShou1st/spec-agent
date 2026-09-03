package com.specagent.eval;

/** Working-focus placement. Focus never selects an Answer. */
public record FocusContextSpec(String focusStepRef, String note) {

    public String canonical() {
        return "focus(" + focusStepRef + "," + note + ")";
    }
}
