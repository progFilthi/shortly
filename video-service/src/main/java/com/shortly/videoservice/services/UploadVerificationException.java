package com.shortly.videoservice.services;

/**
 * An upload did not pass verification: absent, empty, or over the size ceiling.
 * <p>
 * Distinguished from a generic runtime error because the client should see a specific 4xx
 * with an actionable message - "your file is 612 MB, the limit is 500 MB" - rather than a 500.
 */
public class UploadVerificationException extends RuntimeException {

    public enum Kind {
        /** Nothing was ever written to the key. */
        MISSING,
        /** The object exists but has zero bytes. */
        EMPTY,
        /** The object is larger than the platform accepts. */
        TOO_LARGE
    }

    private final Kind kind;
    private final long actualBytes;
    private final long maxBytes;

    public UploadVerificationException(Kind kind, String message, long actualBytes, long maxBytes) {
        super(message);
        this.kind = kind;
        this.actualBytes = actualBytes;
        this.maxBytes = maxBytes;
    }

    public Kind kind() {
        return kind;
    }

    public long actualBytes() {
        return actualBytes;
    }

    public long maxBytes() {
        return maxBytes;
    }
}
