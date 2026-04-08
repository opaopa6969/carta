package org.unlaxer;

import java.util.Objects;

/**
 * First-class event type. Events drive transitions in Carta.
 * Unlike tramli where routing is implicit via requires/produces types,
 * Carta makes events explicit — matching Harel's Statechart formalism.
 */
public final class Event {
    private final String name;

    private Event(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public static Event of(String name) {
        return new Event(name);
    }

    public String name() { return name; }

    @Override public boolean equals(Object o) {
        return o instanceof Event e && name.equals(e.name);
    }

    @Override public int hashCode() { return name.hashCode(); }

    @Override public String toString() { return "Event(" + name + ")"; }
}
