package com.shortly.transcoderservice.storage;

/** Object storage was unreachable or returned an unexpected response. Retryable. */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
