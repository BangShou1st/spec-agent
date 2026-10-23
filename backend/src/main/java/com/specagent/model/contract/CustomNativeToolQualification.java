package com.specagent.model.contract;

/** Host-managed protocol qualification, keyed by exact endpoint and model. No credentials. */
public interface CustomNativeToolQualification {
    boolean hasCustomTargets();
    boolean customQualified(String baseUrl,String model);
}
