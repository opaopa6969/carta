package com.carta;

import java.util.*;

/**
 * Executes a StateMachine instance. Processes events, evaluates guards,
 * fires entry/exit actions on state changes.
 *
 * Entry/exit semantics (Harel Statechart):
 *   - Exiting: exit actions fire from current leaf UP to LCA
 *   - Entering: entry actions fire from LCA DOWN to target leaf
 *   - Self-transition: exit + entry both fire (external self-transition)
 */
public final class CartaEngine {
    private final StateMachine definition;
    private StateNode currentState;
    private final StateContext context;
    private final List<TransitionRecord> log = new ArrayList<>();

    public CartaEngine(StateMachine definition) {
        this.definition = definition;
        this.context = new StateContext();
        // Enter initial state
        this.currentState = definition.resolveToLeaf(definition.root());
        enterState(currentState);
    }

    public CartaEngine(StateMachine definition, StateContext context) {
        this.definition = definition;
        this.context = context;
        this.currentState = definition.resolveToLeaf(definition.root());
        enterState(currentState);
    }

    public StateNode currentState() { return currentState; }
    public StateContext context() { return context; }
    public List<TransitionRecord> log() { return Collections.unmodifiableList(log); }
    public boolean isCompleted() { return currentState.isTerminal(); }

    /**
     * Send an event to the state machine.
     * Returns true if a transition was taken.
     */
    public boolean send(Event event) {
        if (isCompleted()) return false;

        // Find applicable transition: check current state and ancestors
        Transition match = findTransition(currentState, event);
        if (match == null) return false;

        // Execute transition
        StateNode from = currentState;
        StateNode to = definition.state(match.to());
        StateNode targetLeaf = definition.resolveToLeaf(to);

        // Find LCA (Least Common Ancestor)
        StateNode lca = findLCA(from, to);

        // Exit: from current up to LCA
        exitUpTo(from, lca);

        // Execute transition action
        match.execute(context);

        // Enter: from LCA down to target leaf
        enterDownTo(targetLeaf, lca);

        currentState = targetLeaf;
        log.add(new TransitionRecord(from.name(), targetLeaf.name(), event.name()));

        return true;
    }

    /**
     * Send an event with data.
     */
    public boolean send(Event event, String key, Object value) {
        context.put(key, value);
        return send(event);
    }

    private Transition findTransition(StateNode state, Event event) {
        // Check from current state up through ancestors
        for (StateNode s = state; s != null; s = s.parent()) {
            List<Transition> candidates = definition.transitionsFrom(s.name(), event);
            for (Transition t : candidates) {
                if (t.evaluate(context)) return t;
            }
        }
        return null;
    }

    private StateNode findLCA(StateNode a, StateNode b) {
        Set<StateNode> ancestorsA = new LinkedHashSet<>();
        for (StateNode n = a; n != null; n = n.parent()) ancestorsA.add(n);
        for (StateNode n = b; n != null; n = n.parent()) {
            if (ancestorsA.contains(n)) return n;
        }
        return definition.root();
    }

    private void exitUpTo(StateNode from, StateNode lca) {
        for (StateNode n = from; n != null && n != lca; n = n.parent()) {
            n.executeExit(context);
        }
    }

    private void enterDownTo(StateNode target, StateNode lca) {
        // Collect path from LCA to target, then enter top-down
        var path = new ArrayList<StateNode>();
        for (StateNode n = target; n != null && n != lca; n = n.parent()) {
            path.add(0, n);
        }
        for (StateNode n : path) {
            n.executeEntry(context);
        }
    }

    private void enterState(StateNode state) {
        // Enter from root down to leaf
        var path = new ArrayList<StateNode>();
        for (StateNode n = state; n != null; n = n.parent()) path.add(0, n);
        for (StateNode n : path) n.executeEntry(context);
    }

    // ─── Transition Record ──────────────────────────────

    public record TransitionRecord(String from, String to, String event) {
        @Override public String toString() {
            return from + " --[" + event + "]--> " + to;
        }
    }
}
