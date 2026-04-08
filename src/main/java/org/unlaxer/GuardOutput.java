package org.unlaxer;

import java.util.Map;

/**
 * Sealed result of a {@link TransitionGuard} evaluation.
 *
 * <ul>
 *   <li>{@link Accepted} — guard passed; optionally carries typed data to inject into context</li>
 *   <li>{@link Rejected} — guard refused the transition (with reason)</li>
 *   <li>{@link Expired} — flow TTL exceeded</li>
 * </ul>
 */
public sealed interface GuardOutput {

    record Accepted(Map<Class<?>, Object> data) implements GuardOutput {
        public Accepted() { this(Map.of()); }
        public Accepted(Map<Class<?>, Object> data) { this.data = Map.copyOf(data); }
    }

    record Rejected(String reason) implements GuardOutput {}

    record Expired() implements GuardOutput {}

    static Accepted accepted() { return new Accepted(); }
    static Accepted accepted(Map<Class<?>, Object> data) { return new Accepted(data); }
    static Rejected rejected(String reason) { return new Rejected(reason); }
    static Expired expired() { return new Expired(); }
}
