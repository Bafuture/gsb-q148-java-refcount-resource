package com.example.gsb.refcount;

/**
 * Point-in-time snapshot of the manager's counters.
 *
 * @param registeredResources number of handles ever registered and still known
 * @param totalAcquired       total references acquired since startup
 * @param totalReleased       total references released since startup
 * @param activeReferences    references currently held (acquired - released)
 * @param resourcesReleased   underlying resources whose release action ran
 * @param resourcesReclaimed  released resources observed as garbage collected
 *                            via the manager's weak-reference tracking
 */
public record ResourceStats(
        int registeredResources,
        long totalAcquired,
        long totalReleased,
        long activeReferences,
        long resourcesReleased,
        long resourcesReclaimed) {
}
