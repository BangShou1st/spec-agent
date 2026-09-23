package com.specagent.retrieval;

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
