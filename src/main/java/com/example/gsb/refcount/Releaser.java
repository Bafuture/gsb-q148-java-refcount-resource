package com.example.gsb.refcount;

/** Callback invoked exactly once when a resource's reference count reaches zero. */
@FunctionalInterface
public interface Releaser<T> {
    void release(T resource) throws Exception;
}
