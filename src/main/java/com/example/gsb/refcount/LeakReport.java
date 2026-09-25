package com.example.gsb.refcount;

import java.time.Duration;
import java.time.Instant;

/**
 * Describes a reference that has been held longer than the configured leak
 * threshold without the handle's reference count returning to zero.
 */
public record LeakReport(
        String resourceName,
        long referenceId,
        String registrationLocation,
        Instant acquiredAt,
        Duration age) {

    @Override
    public String toString() {
        return "LeakReport{resource='" + resourceName + '\''
                + ", referenceId=" + referenceId
                + ", acquiredAt=" + acquiredAt
                + ", age=" + age
                + ", registeredAt=" + registrationLocation + '}';
    }
}
