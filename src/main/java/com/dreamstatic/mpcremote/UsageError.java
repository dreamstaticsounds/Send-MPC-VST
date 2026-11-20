package com.dreamstatic.mpcremote;

/** A user-facing mistake: bad arguments, ambiguous names, missing files. */
public class UsageError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public UsageError(String message) {
        super(message);
    }
}
