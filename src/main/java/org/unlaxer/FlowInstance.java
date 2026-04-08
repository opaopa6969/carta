package org.unlaxer;

import java.time.Instant;
import java.util.*;

/**
 * Runtime state of a single flow execution.
 *
 * Wraps the current state name, context, guard-failure counters,
 * and timestamps. Can be persisted via {@link FlowStore} and restored
 * to resume a long-lived flow.
 */
public final class FlowInstance {
    private final String id;
    private String currentState;
    private final StateContext context;
    private final Map<String, Integer> guardFailureCounts = new LinkedHashMap<>();
    private final Instant createdAt;
    private Instant updatedAt;

    public FlowInstance(String id, String initialState) {
        this(id, initialState, new StateContext());
    }

    public FlowInstance(String id, String initialState, StateContext context) {
        this.id = id;
        this.currentState = initialState;
        this.context = context;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String id() { return id; }
    public String currentState() { return currentState; }
    public StateContext context() { return context; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    void setCurrentState(String state) {
        this.currentState = state;
        this.updatedAt = Instant.now();
    }

    int guardFailureCount(String guardName) {
        return guardFailureCounts.getOrDefault(guardName, 0);
    }

    void incrementGuardFailure(String guardName) {
        guardFailureCounts.merge(guardName, 1, Integer::sum);
    }

    /** Clear guard failure counts (called on actual state change, not self-transitions). */
    void clearGuardFailures() {
        guardFailureCounts.clear();
    }

    @Override public String toString() {
        return "FlowInstance(" + id + " @ " + currentState + ")";
    }
}
