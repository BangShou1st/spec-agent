package com.specagent.eval;

import java.util.List;

/** Evaluation profile: deterministic B-fast is CI-blocking; live profiles are not. */
public enum EvaluationProfile {
    B_FAST,
    LIVE_PROVIDER,
    JUDGE
}
