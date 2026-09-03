package com.specagent.eval;

import java.util.List;

/**
 * Test-scope port the runner uses to install the B-fast Brain script.
 * Implemented by the scripted brain in eval tests; production wiring
 * never provides it, so the runner fails closed outside eval tests.
 */
public interface BrainScriptInstaller {

    void installScript(BrainScript brainScript, String scenarioId, long seed, int paraphraseIndex);

    void resetScripts();

    List<String> observedStages();

    int providerRetries();
}
