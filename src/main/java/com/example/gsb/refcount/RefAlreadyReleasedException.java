package com.example.gsb.refcount;

/** Thrown when a {@link ResourceRef} is released more than once. */
public final class RefAlreadyReleasedException extends IllegalStateException {
    public RefAlreadyReleasedException(String message) {
        super(message);
    }
}
