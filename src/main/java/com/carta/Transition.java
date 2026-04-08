package com.carta;

import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A transition in Carta: from → on(event) → guard → action → to.
 *
 * Key difference from tramli:
 *   tramli:  guard validates AND produces data (GuardOutput.Accepted{data})
 *   Carta:   guard is a pure predicate, action is a separate side-effect
 *
 * This separation follows Harel's original Statechart formalism where
 * guards are boolean conditions and actions are effects.
 */
public final class Transition {
    private final String from;
    private final String to;
    private final Event event;
    private final Predicate<StateContext> guard;    // pure condition (no side effects)
    private final Consumer<StateContext> action;    // side effect (ctx mutation)

    Transition(String from, String to, Event event,
               Predicate<StateContext> guard, Consumer<StateContext> action) {
        this.from = from;
        this.to = to;
        this.event = event;
        this.guard = guard;
        this.action = action;
    }

    public String from() { return from; }
    public String to() { return to; }
    public Event event() { return event; }

    public boolean evaluate(StateContext ctx) {
        return guard == null || guard.test(ctx);
    }

    public void execute(StateContext ctx) {
        if (action != null) action.accept(ctx);
    }

    @Override public String toString() {
        return from + " --[" + event.name() + "]--> " + to;
    }
}
