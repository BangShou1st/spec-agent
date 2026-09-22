package com.specagent.retrieval.api;

/** Authority is independent from lexical/vector relevance. */
public enum MemoryAuthority {
    CONFIRMED,
    USER_AUTHORED,
    EXTERNAL_EVIDENCE,
    DERIVED,
    ASSUMED,
    UNRESOLVED,
    REJECTED
}
