package com.example.gsb.refcount;

/**
 * Thrown when an operation is attempted on a reference that has already been
 * released, most notably releasing the same reference twice.
 */
public class ResourceReleasedException extends IllegalStateException {

    public ResourceReleasedException(String message) {
        super(message);
    }
}
