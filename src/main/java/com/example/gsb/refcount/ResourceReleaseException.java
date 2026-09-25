package com.example.gsb.refcount;

/** Thrown when the user-supplied {@link Releaser} itself fails. */
public final class ResourceReleaseException extends RuntimeException {
    public ResourceReleaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
