package com.miningdim.webui.server;

import java.util.Objects;

/** Expected business rejection returned to WebUI without a warning stack trace. */
public final class WebUiBusinessException extends RuntimeException {

    private final String errorCode;
    private final boolean retrySameOpeningId;

    public WebUiBusinessException(String errorCode, String message, boolean retrySameOpeningId) {
        // Expected validation failures can be attacker-driven; avoid allocating a stack trace for each rejection.
        super(Objects.requireNonNull(message, "message"), null, false, false);
        if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("WebUI business error code must not be blank");
        }
        this.errorCode = errorCode;
        this.retrySameOpeningId = retrySameOpeningId;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retrySameOpeningId() {
        return retrySameOpeningId;
    }
}
