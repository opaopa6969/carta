package org.unlaxer;

import java.util.*;

/**
 * In-memory {@link FlowStore} for testing and single-process use.
 */
public final class InMemoryFlowStore implements FlowStore {
    private final Map<String, FlowInstance> store = new LinkedHashMap<>();

    @Override
    public void save(FlowInstance instance) {
        store.put(instance.id(), instance);
    }

    @Override
    public Optional<FlowInstance> load(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public void delete(String id) {
        store.remove(id);
    }

    public int size() { return store.size(); }

    public Collection<FlowInstance> all() {
        return Collections.unmodifiableCollection(store.values());
    }
}
