package com.shortly.transcoderservice.storage;

/** The object referenced by a job is not in the bucket. Usually a lost or expired upload. */
public class SourceObjectMissingException extends RuntimeException {

    private final String key;

    public SourceObjectMissingException(String key, Throwable cause) {
        super("Source object not found: " + key, cause);
        this.key = key;
    }

    public String key() {
        return key;
    }
}
