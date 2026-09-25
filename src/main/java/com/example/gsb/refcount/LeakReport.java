package com.example.gsb.refcount;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Describes a resource whose references have not returned to zero within the configured time. */
public final class LeakReport {
    private final String description;
    private final int outstandingRefs;
    private final Instant registeredAt;
    private final Duration age;
    private final List<StackTraceElement> registrationSite;

    LeakReport(String description, int outstandingRefs, Instant registeredAt, Duration age,
               List<StackTraceElement> registrationSite) {
        this.description = description;
        this.outstandingRefs = outstandingRefs;
        this.registeredAt = registeredAt;
        this.age = age;
        this.registrationSite = registrationSite;
    }

    public String description() {
        return description;
    }

    public int outstandingRefs() {
        return outstandingRefs;
    }

    public Instant registeredAt() {
        return registeredAt;
    }

    public Duration age() {
        return age;
    }

    /** Full stack trace captured at registration time. */
    public List<StackTraceElement> registrationSite() {
        return registrationSite;
    }

    /** The first application frame of the registration stack trace, e.g. {@code com.foo.Bar.open(Bar.java:42)}. */
    public String registrationLocation() {
        return registrationSite.isEmpty() ? "<unknown>" : registrationSite.get(0).toString();
    }

    @Override
    public String toString() {
        return "LeakReport{resource=" + description
                + ", outstandingRefs=" + outstandingRefs
                + ", age=" + age
                + ", registeredAt=" + registrationLocation() + "}";
    }
}
