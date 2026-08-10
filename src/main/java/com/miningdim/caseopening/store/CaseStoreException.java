package com.miningdim.caseopening.store;

/** Unchecked storage boundary exception; WebUI gateway or server startup owns final reporting. */
public final class CaseStoreException extends RuntimeException {
    public CaseStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public CaseStoreException(String message) {
        super(message);
    }
}
