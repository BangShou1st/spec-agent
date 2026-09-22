package com.specagent.retrieval.api;

/** Whitelisted source families that may enter model-visible retrieval. */
public enum RetrievalSourceKind {
    NODE,
    ANSWER,
    CLAIM,
    RESOURCE_CHUNK,
    CAPABILITY_OBSERVATION,
    ROUTE_SUMMARY
}
