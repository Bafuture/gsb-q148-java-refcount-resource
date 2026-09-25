package com.example.gsb.refcount;

/** Thrown when accessing a resource whose reference count has already reached zero. */
public final class ResourceReleasedException extends IllegalStateException {
    public ResourceReleasedException(String message) {
        super(message);
    }
}
