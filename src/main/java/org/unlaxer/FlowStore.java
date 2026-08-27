package org.unlaxer;

import java.util.Optional;

/**
 * Pluggable persistence for {@link FlowInstance}s.
 *
 * Implementations: {@link InMemoryFlowStore} (default),
 * JDBC, Redis, etc. (user-provided).
 *
 * <h2>Consistency and concurrency contract</h2>
 * Each {@link #save(FlowInstance)}, {@link #load(String)}, or
 * {@link #delete(String)} call must act as one atomic store operation for its
 * flow ID: a load must observe either the value before a completed operation
 * or the value after it, never an intermediate store state. This interface
 * does not make a sequence of calls transactional and does not require a deep
 * snapshot of the mutable {@link FlowInstance} or its context.
 *
 * <p>Concurrent invocation is not guaranteed by this interface. An
 * implementation must document whether it supports concurrent calls; callers
 * must otherwise serialize access externally.</p>
 */
public interface FlowStore {
    void save(FlowInstance instance);
    Optional<FlowInstance> load(String id);
    void delete(String id);
}
