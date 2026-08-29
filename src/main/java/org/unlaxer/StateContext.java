package org.unlaxer;

import java.util.*;

/**
 * Mutable context for Carta state machines.
 *
 * Dual-mode access:
 * <ul>
 *   <li><b>String-keyed</b> (Harel mode) — {@code put("key", value)}, {@code get("key", Type.class)}</li>
 *   <li><b>Type-keyed</b> (tramli mode) — {@code put(Type.class, value)}, {@code get(Type.class)}</li>
 * </ul>
 *
 * Both modes coexist in the same context. Type-keyed access enables
 * build-time data-flow verification via requires/produces contracts.
 *
 * <p>This class is mutable and not thread-safe. A context is intended to be
 * accessed by one thread at a time. If it is shared between threads, callers
 * must serialize every read, write, and iteration (including access through a
 * {@link CartaEngine}) with the same external lock.</p>
 */
public final class StateContext {
    private final Map<String, Object> attrs = new LinkedHashMap<>();
    private final Map<Class<?>, Object> typed = new LinkedHashMap<>();

    // ─── String-keyed (Harel mode) ─────────────────────────

    public void put(String key, Object value) {
        attrs.put(key, value);
    }

    public <T> T get(String key, Class<T> type) {
        Object v = attrs.get(key);
        if (v == null) throw new CartaException("Missing context key: " + key);
        return type.cast(v);
    }

    public <T> Optional<T> find(String key, Class<T> type) {
        Object v = attrs.get(key);
        if (v == null || !type.isInstance(v)) return Optional.empty();
        return Optional.of(type.cast(v));
    }

    public boolean has(String key) { return attrs.containsKey(key); }

    // ─── Type-keyed (tramli mode) ──────────────────────────

    public <T> void put(Class<T> type, T value) {
        typed.put(type, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        Object v = typed.get(type);
        if (v == null) throw new CartaException("Missing context type: " + type.getSimpleName());
        return (T) v;
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<T> find(Class<T> type) {
        Object v = typed.get(type);
        return v == null ? Optional.empty() : Optional.of((T) v);
    }

    public boolean has(Class<?> type) { return typed.containsKey(type); }

    public Set<Class<?>> availableTypes() { return Collections.unmodifiableSet(typed.keySet()); }

    // ─── Bulk operations ───────────────────────────────────

    void putAllTyped(Map<Class<?>, Object> data) {
        typed.putAll(data);
    }

    /**
     * Capture the current typed values for the given key set (keys absent
     * from the context are omitted from the result). Used to take a
     * rollback snapshot before temporarily injecting external data.
     */
    Map<Class<?>, Object> captureTypedSnapshot(Set<Class<?>> keys) {
        var snap = new LinkedHashMap<Class<?>, Object>();
        for (Class<?> k : keys) {
            Object v = typed.get(k);
            if (v != null) snap.put(k, v);
        }
        return snap;
    }

    /**
     * Restore the typed slot to a previously captured snapshot within the
     * given scope: every key in {@code scope} is removed first, then the
     * entries of {@code snapshot} are put back. Keys present in the scope
     * but absent from the snapshot end up removed; keys present in the
     * snapshot are restored to their captured value.
     */
    void restoreTyped(Map<Class<?>, Object> snapshot, Set<Class<?>> scope) {
        for (Class<?> k : scope) typed.remove(k);
        typed.putAll(snapshot);
    }

    public Map<String, Object> snapshot() {
        var snap = new LinkedHashMap<String, Object>(attrs);
        for (var entry : typed.entrySet()) {
            snap.put("@" + entry.getKey().getSimpleName(), entry.getValue());
        }
        return snap;
    }
}
