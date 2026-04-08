package com.carta;

import java.util.*;

/**
 * Mutable context bag for Carta state machines.
 * Unlike tramli's TypeId-keyed FlowContext, Carta uses string keys
 * for simplicity — events carry their own typed data.
 */
public final class StateContext {
    private final Map<String, Object> attrs = new LinkedHashMap<>();

    public void put(String key, Object value) {
        attrs.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        Object v = attrs.get(key);
        if (v == null) throw new CartaException("Missing context key: " + key);
        return type.cast(v);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(String key, Class<T> type) {
        Object v = attrs.get(key);
        if (v == null || !type.isInstance(v)) return Optional.empty();
        return Optional.of(type.cast(v));
    }

    public boolean has(String key) { return attrs.containsKey(key); }

    public Map<String, Object> snapshot() { return new LinkedHashMap<>(attrs); }
}
