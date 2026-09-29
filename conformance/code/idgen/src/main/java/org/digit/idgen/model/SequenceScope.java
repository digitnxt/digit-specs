package org.digit.idgen.model;

/** When the sequence counter resets to its start value. */
public enum SequenceScope {
    /** Never resets — a real Postgres sequence increments indefinitely. */
    GLOBAL,
    DAILY,
    MONTHLY,
    YEARLY
}
