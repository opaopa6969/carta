package com.carta;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Immutable definition of a hierarchical state machine.
 * Built via {@link Carta#define(String)}.
 */
public final class StateMachine {
    private final String name;
    private final StateNode root;
    private final List<Transition> transitions;
    private final Map<String, StateNode> stateIndex;

    StateMachine(String name, StateNode root, List<Transition> transitions) {
        this.name = name;
        this.root = root;
        this.transitions = List.copyOf(transitions);
        this.stateIndex = new LinkedHashMap<>();
        indexStates(root);
        validate();
    }

    private void indexStates(StateNode node) {
        stateIndex.put(node.name(), node);
        for (StateNode child : node.children()) indexStates(child);
    }

    public String name() { return name; }
    public StateNode root() { return root; }
    public List<Transition> transitions() { return transitions; }

    public StateNode state(String name) {
        StateNode s = stateIndex.get(name);
        if (s == null) throw new CartaException("UNKNOWN_STATE", "State not found: " + name);
        return s;
    }

    public Optional<StateNode> findState(String name) {
        return Optional.ofNullable(stateIndex.get(name));
    }

    public List<Transition> transitionsFrom(String state, Event event) {
        return transitions.stream()
            .filter(t -> t.from().equals(state) && t.event().equals(event))
            .toList();
    }

    /** Resolve to leaf: if composite, descend to initial children. */
    public StateNode resolveToLeaf(StateNode node) {
        while (node.isComposite()) {
            node = node.initialChild()
                .orElseThrow(() -> new CartaException("NO_INITIAL",
                    "Composite state " + node.name() + " has no initial child"));
        }
        return node;
    }

    /** Ancestors from node up to (excluding) ancestor. */
    public List<StateNode> pathTo(StateNode from, StateNode ancestor) {
        var path = new ArrayList<StateNode>();
        for (StateNode n = from; n != null && n != ancestor; n = n.parent()) {
            path.add(n);
        }
        return path;
    }

    /** Generate Mermaid stateDiagram-v2. */
    public String toMermaid() {
        var sb = new StringBuilder("stateDiagram-v2\n");
        renderMermaid(sb, root, "    ");
        for (Transition t : transitions) {
            sb.append("    ").append(t.from()).append(" --> ").append(t.to())
              .append(" : ").append(t.event().name()).append("\n");
        }
        return sb.toString();
    }

    private void renderMermaid(StringBuilder sb, StateNode node, String indent) {
        if (node.isInitial() && node.parent() != null) {
            sb.append(indent).append("[*] --> ").append(node.name()).append("\n");
        }
        if (node.isTerminal()) {
            sb.append(indent).append(node.name()).append(" --> [*]\n");
        }
        if (node.isComposite()) {
            sb.append(indent).append("state ").append(node.name()).append(" {\n");
            for (StateNode child : node.children()) renderMermaid(sb, child, indent + "    ");
            sb.append(indent).append("}\n");
        }
    }

    // ─── Validation ─────────────────────────────────────

    private void validate() {
        var errors = new ArrayList<String>();
        // All transition endpoints exist
        for (Transition t : transitions) {
            if (!stateIndex.containsKey(t.from()))
                errors.add("Transition from unknown state: " + t.from());
            if (!stateIndex.containsKey(t.to()))
                errors.add("Transition to unknown state: " + t.to());
        }
        // Composite states have initial child
        for (StateNode node : stateIndex.values()) {
            if (node.isComposite() && node.initialChild().isEmpty())
                errors.add("Composite state " + node.name() + " has no initial child");
        }
        // Terminal states have no outgoing transitions
        for (Transition t : transitions) {
            StateNode from = stateIndex.get(t.from());
            if (from != null && from.isTerminal())
                errors.add("Terminal state " + t.from() + " has outgoing transition");
        }
        if (!errors.isEmpty()) {
            throw new CartaException("INVALID_DEFINITION",
                name + " has " + errors.size() + " error(s):\n  - " +
                String.join("\n  - ", errors));
        }
    }

    // ─── Builder ─────────────────────────────────────────

    public static Builder builder(String name) { return new Builder(name); }

    public static class Builder {
        private final String machineName;
        private StateNode root;
        private StateNode currentParent;
        private final List<Transition> transitions = new ArrayList<>();

        Builder(String name) { this.machineName = name; }

        /** Create the root state and enter it. */
        public Builder root(String name) {
            root = new StateNode(name, null, false);
            currentParent = root;
            return this;
        }

        /** Add an initial child state. */
        public Builder initial(String name) {
            var child = new StateNode(name, currentParent, false);
            child.setInitial(true);
            currentParent.addChild(child);
            return this;
        }

        /** Add a non-initial child state. */
        public Builder state(String name) {
            var child = new StateNode(name, currentParent, false);
            currentParent.addChild(child);
            currentParent = child;
            return this;
        }

        /** Add a terminal child state. */
        public Builder terminal(String name) {
            var child = new StateNode(name, currentParent, true);
            currentParent.addChild(child);
            return this;
        }

        /** Set entry action for current composite state. */
        public Builder onEntry(Consumer<StateContext> action) {
            currentParent.setEntryAction(action);
            return this;
        }

        /** Set exit action for current composite state. */
        public Builder onExit(Consumer<StateContext> action) {
            currentParent.setExitAction(action);
            return this;
        }

        /** Go back up one level in the hierarchy. */
        public Builder end() {
            if (currentParent.parent() != null) currentParent = currentParent.parent();
            return this;
        }

        /** Start defining a transition. */
        public TransitionBuilder transition() { return new TransitionBuilder(this); }

        public StateMachine build() {
            if (root == null) throw new CartaException("No root state defined");
            return new StateMachine(machineName, root, transitions);
        }
    }

    public static class TransitionBuilder {
        private final Builder parent;
        private String from;
        private String to;
        private Event event;
        private Predicate<StateContext> guard;
        private Consumer<StateContext> action;

        TransitionBuilder(Builder parent) { this.parent = parent; }

        public TransitionBuilder from(String s) { this.from = s; return this; }
        public TransitionBuilder on(Event e) { this.event = e; return this; }
        public TransitionBuilder guard(Predicate<StateContext> g) { this.guard = g; return this; }
        public TransitionBuilder action(Consumer<StateContext> a) { this.action = a; return this; }

        public Builder to(String s) {
            this.to = s;
            parent.transitions.add(new Transition(from, to, event, guard, action));
            return parent;
        }
    }
}
