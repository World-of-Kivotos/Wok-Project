package com.miningdim.caseopening.store;

/** Persisted Saga phases. Rows are never deleted, so an opening ID remains durable and idempotent. */
public enum CaseOpeningStatus {
    RESERVED,
    DEBITED,
    COMMITTED,
    REFUNDED
}
