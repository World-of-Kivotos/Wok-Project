package com.miningdim.caseopening;

/** Runtime locator populated at server start and cleared before the SQLite connection closes. */
public final class CaseServices {

    private static volatile CaseOpeningService service;

    private CaseServices() {
    }

    public static void register(CaseOpeningService next) {
        if (next == null) {
            throw new IllegalArgumentException("case opening service must not be null");
        }
        if (service != null) {
            throw new IllegalStateException("case opening service already registered");
        }
        service = next;
    }

    public static boolean isRegistered() {
        return service != null;
    }

    public static CaseOpeningService service() {
        CaseOpeningService current = service;
        if (current == null) {
            throw new IllegalStateException("case opening service is not ready");
        }
        return current;
    }

    public static void reset() {
        service = null;
    }
}
