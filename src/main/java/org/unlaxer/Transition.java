package org.unlaxer;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A transition in Carta. Supports four types:
 *
 * <table>
 *   <tr><th>Type</th><th>Trigger</th><th>Origin</th></tr>
 *   <tr><td>EVENT</td><td>Explicit {@link Event}</td><td>Harel Statechart</td></tr>
 *   <tr><td>AUTO</td><td>Fires immediately</td><td>tramli</td></tr>
 *   <tr><td>EXTERNAL</td><td>External data via resume()</td><td>tramli</td></tr>
 *   <tr><td>BRANCH</td><td>Conditional label routing</td><td>tramli</td></tr>
 * </table>
 */
public final class Transition {

    public enum Type { EVENT, AUTO, EXTERNAL, BRANCH }

    private final Type type;
    private final String from;
    private final String to;
    private final Event event;
    private final Predicate<StateContext> guard;
    private final Consumer<StateContext> action;
    private final StateProcessor processor;
    private final TransitionGuard transitionGuard;
    private final BranchProcessor branchProcessor;
    private final Map<String, String> branchTargets;

    // EVENT (backward compatible)
    Transition(String from, String to, Event event,
               Predicate<StateContext> guard, Consumer<StateContext> action) {
        this.type = Type.EVENT;
        this.from = from;
        this.to = to;
        this.event = event;
        this.guard = guard;
        this.action = action;
        this.processor = null;
        this.transitionGuard = null;
        this.branchProcessor = null;
        this.branchTargets = null;
    }

    // AUTO
    Transition(String from, String to, StateProcessor processor) {
        this.type = Type.AUTO;
        this.from = from;
        this.to = to;
        this.processor = processor;
        this.event = null;
        this.guard = null;
        this.action = null;
        this.transitionGuard = null;
        this.branchProcessor = null;
        this.branchTargets = null;
    }

    // EXTERNAL
    Transition(String from, String to, TransitionGuard transitionGuard) {
        this.type = Type.EXTERNAL;
        this.from = from;
        this.to = to;
        this.transitionGuard = transitionGuard;
        this.event = null;
        this.guard = null;
        this.action = null;
        this.processor = null;
        this.branchProcessor = null;
        this.branchTargets = null;
    }

    // BRANCH
    Transition(String from, BranchProcessor branchProcessor, Map<String, String> targets) {
        this.type = Type.BRANCH;
        this.from = from;
        this.to = null;
        this.branchProcessor = branchProcessor;
        this.branchTargets = Map.copyOf(targets);
        this.event = null;
        this.guard = null;
        this.action = null;
        this.processor = null;
        this.transitionGuard = null;
    }

    public Type type() { return type; }
    public String from() { return from; }
    public String to() { return to; }
    public Event event() { return event; }
    public StateProcessor processor() { return processor; }
    public TransitionGuard transitionGuard() { return transitionGuard; }
    public BranchProcessor branchProcessor() { return branchProcessor; }
    public Map<String, String> branchTargets() { return branchTargets; }

    /** Evaluate EVENT guard (Harel mode). */
    public boolean evaluate(StateContext ctx) {
        return guard == null || guard.test(ctx);
    }

    /** Execute EVENT action (Harel mode). */
    public void execute(StateContext ctx) {
        if (action != null) action.accept(ctx);
    }

    @Override public String toString() {
        return switch (type) {
            case EVENT -> from + " --[" + event.name() + "]--> " + to;
            case AUTO -> from + " --[auto]--> " + to;
            case EXTERNAL -> from + " --[" + transitionGuard.name() + "]--> " + to;
            case BRANCH -> from + " --[branch]--> " + branchTargets;
        };
    }
}
