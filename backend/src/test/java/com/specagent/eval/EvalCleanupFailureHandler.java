package com.specagent.eval;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;

import java.util.function.Consumer;

/** Runs project cleanup while the original test failure is still available. */
final class EvalCleanupFailureHandler implements TestExecutionExceptionHandler {

    private final Consumer<Throwable> cleanup;

    EvalCleanupFailureHandler(Consumer<Throwable> cleanup) {
        this.cleanup = cleanup;
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable testFailure)
            throws Throwable {
        cleanup.accept(testFailure);
        throw testFailure;
    }
}
