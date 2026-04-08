package org.unlaxer;

import java.util.Optional;

/**
 * Pluggable persistence for {@link FlowInstance}s.
 *
 * Implementations: {@link InMemoryFlowStore} (default),
 * JDBC, Redis, etc. (user-provided).
 */
public interface FlowStore {
    void save(FlowInstance instance);
    Optional<FlowInstance> load(String id);
    void delete(String id);
}
