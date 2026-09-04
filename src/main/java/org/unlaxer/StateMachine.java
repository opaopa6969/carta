package org.unlaxer;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Immutable definition of a state machine.
 *
 * Supports both Harel Statechart mode (hierarchical states, explicit events,
 * entry/exit actions) and tramli mode (auto/external/branch transitions,
 * requires/produces data-flow verification).
 *
 * Built via {@link Carta#define(String)}.
 */
public final class StateMachine {
    private final String name;
    private final StateNode root;
    private final List<Transition> transitions;
    private final Map<String, StateNode> stateIndex;

    // Pre-built indices for O(1) lookup by (state, type) and (state, event, type).
    // Built once at construction; immutable afterwards. This is the key
    // optimization that lets send()/autoChain() avoid stream() + toList()
    // allocations on the hot path.
    private final Map<String, List<Transition>> autoByState;
    private final Map<String, List<Transition>> externalByState;
    private final Map<String, List<Transition>> branchByState;
    private final Map<String, Map<Event, List<Transition>>> eventByStateAndEvent;
    private static final List<Transition> EMPTY = List.of();

    StateMachine(String name, StateNode root, List<Transition> transitions) {
        this.name = name;
        this.root = root;
        this.transitions = List.copyOf(transitions);
        this.stateIndex = new LinkedHashMap<>();
        indexStates(root);
        this.autoByState = new LinkedHashMap<>();
        this.externalByState = new LinkedHashMap<>();
        this.branchByState = new LinkedHashMap<>();
        this.eventByStateAndEvent = new LinkedHashMap<>();
        indexTransitions();
        validate();
    }

    private void indexStates(StateNode node) {
        stateIndex.put(node.name(), node);
        for (StateNode child : node.children()) indexStates(child);
    }

    private void indexTransitions() {
        for (Transition t : transitions) {
            switch (t.type()) {
                case EVENT -> eventByStateAndEvent
                    .computeIfAbsent(t.from(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(t.event(), k -> new ArrayList<>())
                    .add(t);
                case AUTO -> autoByState
                    .computeIfAbsent(t.from(), k -> new ArrayList<>())
                    .add(t);
                case EXTERNAL -> externalByState
                    .computeIfAbsent(t.from(), k -> new ArrayList<>())
                    .add(t);
                case BRANCH -> branchByState
                    .computeIfAbsent(t.from(), k -> new ArrayList<>())
                    .add(t);
            }
        }
        // Freeze all index lists into immutable List.of variants
        for (var e : autoByState.entrySet()) e.setValue(List.copyOf(e.getValue()));
        for (var e : externalByState.entrySet()) e.setValue(List.copyOf(e.getValue()));
        for (var e : branchByState.entrySet()) e.setValue(List.copyOf(e.getValue()));
        for (var e : eventByStateAndEvent.entrySet()) {
            Map<Event, List<Transition>> frozen = new LinkedHashMap<>();
            for (var ee : e.getValue().entrySet()) {
                frozen.put(ee.getKey(), List.copyOf(ee.getValue()));
            }
            e.setValue(Map.copyOf(frozen));
        }
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

    /** Event-driven transitions from a state (Harel mode). */
    public List<Transition> transitionsFrom(String state, Event event) {
        Map<Event, List<Transition>> byEvent = eventByStateAndEvent.get(state);
        if (byEvent == null) return EMPTY;
        List<Transition> list = byEvent.get(event);
        return list == null ? EMPTY : list;
    }

    /** Auto transitions from a state. */
    public List<Transition> autoTransitionsFrom(String state) {
        return autoByState.getOrDefault(state, EMPTY);
    }

    /** External transitions from a state. */
    public List<Transition> externalTransitionsFrom(String state) {
        return externalByState.getOrDefault(state, EMPTY);
    }

    /** Branch transitions from a state. */
    public List<Transition> branchTransitionsFrom(String state) {
        return branchByState.getOrDefault(state, EMPTY);
    }

    /** Resolve to leaf: if composite, descend to initial children. */
    public StateNode resolveToLeaf(StateNode node) {
        StateNode current = node;
        while (current.isComposite()) {
            StateNode c = current;
            current = current.initialChild()
                .orElseThrow(() -> new CartaException("NO_INITIAL",
                    "Composite state " + c.name() + " has no initial child"));
        }
        return current;
    }

    /** Ancestors from node up to (excluding) ancestor. */
    public List<StateNode> pathTo(StateNode from, StateNode ancestor) {
        var path = new ArrayList<StateNode>();
        for (StateNode n = from; n != null && n != ancestor; n = n.parent()) {
            path.add(n);
        }
        return path;
    }

    /** Build a {@link DataFlowGraph} for this machine. */
    public DataFlowGraph dataFlowGraph() {
        return new DataFlowGraph(this);
    }

    // ─── Mermaid Generation ────────────────────────────────

    /** Generate Mermaid stateDiagram-v2 with all transition types. */
    public String toMermaid() {
        var sb = new StringBuilder("stateDiagram-v2\n");
        renderMermaid(sb, root, "    ");
        for (Transition t : transitions) {
            switch (t.type()) {
                case EVENT -> sb.append("    ").append(t.from())
                    .append(" --> ").append(t.to())
                    .append(" : ").append(t.event().name()).append("\n");
                case AUTO -> sb.append("    ").append(t.from())
                    .append(" --> ").append(t.to())
                    .append(" : [auto]").append("\n");
                case EXTERNAL -> sb.append("    ").append(t.from())
                    .append(" --> ").append(t.to())
                    .append(" : ").append(t.transitionGuard().name()).append("\n");
                case BRANCH -> {
                    for (var entry : t.branchTargets().entrySet()) {
                        sb.append("    ").append(t.from())
                            .append(" --> ").append(entry.getValue())
                            .append(" : [").append(entry.getKey()).append("]\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Generate data-flow Mermaid diagram showing requires/produces. */
    public String toDataFlowMermaid() {
        var sb = new StringBuilder("flowchart LR\n");
        int idx = 0;
        for (Transition t : transitions) {
            Set<Class<?>> req = Set.of();
            Set<Class<?>> prod = Set.of();
            String label = "";

            switch (t.type()) {
                case AUTO -> {
                    if (t.processor() != null) {
                        req = t.processor().requires();
                        prod = t.processor().produces();
                    }
                    label = "auto";
                }
                case EXTERNAL -> {
                    if (t.transitionGuard() != null) req = t.transitionGuard().requires();
                    label = t.transitionGuard().name();
                }
                case BRANCH -> {
                    if (t.branchProcessor() != null) req = t.branchProcessor().requires();
                    label = "branch";
                }
                default -> { continue; }
            }

            String procId = "p" + idx++;
            sb.append("    ").append(t.from()).append(" --> ").append(procId)
              .append("[\"").append(label).append("\"]")
              .append(" --> ").append(t.to() != null ? t.to() : "...").append("\n");

            for (Class<?> r : req) {
                sb.append("    ").append(r.getSimpleName()).append("-.->").append(procId).append("\n");
            }
            for (Class<?> p : prod) {
                sb.append("    ").append(procId).append("-.->").append(p.getSimpleName()).append("\n");
            }
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

    // ─── Validation ─────────────────────────────────────────

    private void validate() {
        var errors = new ArrayList<String>();
        validateEndpoints(errors);
        validateCompositeInitials(errors);
        validateTerminalOutgoing(errors);
        validateAutoDAG(errors);
        validateExternalGuardNames(errors);
        validateBranchTargets(errors);
        validateDataFlowChain(errors);
        if (!errors.isEmpty()) {
            throw new CartaException("INVALID_DEFINITION",
                name + " has " + errors.size() + " error(s):\n  - " +
                String.join("\n  - ", errors));
        }
    }

    /** V1: All transition endpoints exist. */
    private void validateEndpoints(List<String> errors) {
        for (Transition t : transitions) {
            if (!stateIndex.containsKey(t.from()))
                errors.add("Transition from unknown state: " + t.from());
            if (t.to() != null && !stateIndex.containsKey(t.to()))
                errors.add("Transition to unknown state: " + t.to());
        }
    }

    /** V2: Composite states have exactly one initial child. */
    private void validateCompositeInitials(List<String> errors) {
        for (StateNode node : stateIndex.values()) {
            if (node.isComposite()) {
                long initials = node.children().stream().filter(c -> c.isInitial()).count();
                if (initials == 0) {
                    errors.add("Composite state " + node.name() + " has no initial child");
                } else if (initials > 1) {
                    errors.add("Composite state " + node.name() + " has " + initials +
                        " initial children — exactly one is required");
                }
            }
        }
    }

    /** V3: No transitions from terminal states. */
    private void validateTerminalOutgoing(List<String> errors) {
        for (Transition t : transitions) {
            StateNode from = stateIndex.get(t.from());
            if (from != null && from.isTerminal())
                errors.add("Terminal state " + t.from() + " has outgoing transition");
        }
    }

    /**
     * V4: Auto/Branch transitions form a DAG (no cycles).
     *
     * <p>Targets are resolved to their leaf the same way {@link #resolveToLeaf}
     * does at runtime, so a chain that returns to a state through a composite's
     * initial child is detected here instead of silently cutting the auto-chain
     * short at execution time. Sources are <em>not</em> resolved: the engine
     * looks up auto/branch transitions by exact state name, so resolving them
     * would invent edges the engine never walks.</p>
     */
    private void validateAutoDAG(List<String> errors) {
        // Build adjacency for auto + branch transitions only
        Map<String, Set<String>> adj = new LinkedHashMap<>();
        for (Transition t : transitions) {
            if (t.type() == Transition.Type.AUTO) {
                adj.computeIfAbsent(t.from(), k -> new LinkedHashSet<>())
                    .add(resolveTargetForValidation(t.to()));
            } else if (t.type() == Transition.Type.BRANCH) {
                for (String target : t.branchTargets().values()) {
                    adj.computeIfAbsent(t.from(), k -> new LinkedHashSet<>())
                        .add(resolveTargetForValidation(target));
                }
            }
        }
        // DFS cycle detection
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String node : adj.keySet()) {
            if (hasCycle(node, adj, visited, inStack)) {
                errors.add("Auto/Branch transitions form a cycle involving: " + node +
                    " (resolved auto/branch targets: " + String.join(", ", adj.get(node)) + ")");
                return;
            }
        }
    }

    /**
     * Resolve an auto/branch target the way the engine will at runtime —
     * descending a composite state to its initial child, repeatedly.
     *
     * <p>Returns the declared name unchanged when it cannot be resolved (an
     * unknown state, or a composite with no initial child) so that V1 and V2
     * report those problems instead of this check failing on them.</p>
     */
    private String resolveTargetForValidation(String name) {
        StateNode node = stateIndex.get(name);
        if (node == null) return name;
        var seen = new HashSet<String>();
        while (node.isComposite()) {
            if (!seen.add(node.name())) return name;
            Optional<StateNode> initial = node.initialChild();
            if (initial.isEmpty()) return name;
            node = initial.get();
        }
        return node.name();
    }

    private boolean hasCycle(String node, Map<String, Set<String>> adj,
                             Set<String> visited, Set<String> inStack) {
        if (inStack.contains(node)) return true;
        if (visited.contains(node)) return false;
        visited.add(node);
        inStack.add(node);
        for (String next : adj.getOrDefault(node, Set.of())) {
            if (hasCycle(next, adj, visited, inStack)) return true;
        }
        inStack.remove(node);
        return false;
    }

    /** V5: External guard names unique per source state (DD-020). */
    private void validateExternalGuardNames(List<String> errors) {
        Map<String, Set<String>> guardNamesByState = new LinkedHashMap<>();
        for (Transition t : transitions) {
            if (t.type() == Transition.Type.EXTERNAL) {
                String guardName = t.transitionGuard().name();
                Set<String> names = guardNamesByState.computeIfAbsent(t.from(), k -> new LinkedHashSet<>());
                if (!names.add(guardName)) {
                    errors.add("Duplicate guard name '" + guardName + "' on state " + t.from());
                }
            }
        }
    }

    /** V6: All branch labels map to existing states. */
    private void validateBranchTargets(List<String> errors) {
        for (Transition t : transitions) {
            if (t.type() == Transition.Type.BRANCH) {
                for (var entry : t.branchTargets().entrySet()) {
                    if (!stateIndex.containsKey(entry.getValue())) {
                        errors.add("Branch label '" + entry.getKey() +
                            "' maps to unknown state: " + entry.getValue());
                    }
                }
            }
        }
    }

    /** V7: requires/produces chain integrity. */
    private void validateDataFlowChain(List<String> errors) {
        Set<Class<?>> allProduced = new LinkedHashSet<>();
        Set<Class<?>> allRequired = new LinkedHashSet<>();

        for (Transition t : transitions) {
            if (t.processor() != null) {
                allRequired.addAll(t.processor().requires());
                allProduced.addAll(t.processor().produces());
            }
            if (t.transitionGuard() != null) {
                // External guard requires are provided via resume() external data
                allProduced.addAll(t.transitionGuard().requires());
            }
            if (t.branchProcessor() != null) {
                allRequired.addAll(t.branchProcessor().requires());
            }
        }

        for (Class<?> req : allRequired) {
            if (!allProduced.contains(req)) {
                errors.add("Data-flow: " + req.getSimpleName() +
                    " is required but never produced");
            }
        }
    }

    // ─── Builder ─────────────────────────────────────────────

    public static Builder builder(String name) { return new Builder(name); }

    public static class Builder {
        private final String machineName;
        private StateNode root;
        private StateNode currentParent;
        private StateNode lastCreated;
        private final List<Transition> transitions = new ArrayList<>();

        Builder(String name) { this.machineName = name; }

        // ── Hierarchy (Harel mode) ──────────────────────────

        public Builder root(String name) {
            root = new StateNode(name, null, false);
            currentParent = root;
            lastCreated = root;
            return this;
        }

        public Builder initial(String name) {
            var child = new StateNode(name, currentParent, false);
            child.setInitial(true);
            currentParent.addChild(child);
            lastCreated = child;
            return this;
        }

        public Builder state(String name) {
            var child = new StateNode(name, currentParent, false);
            currentParent.addChild(child);
            currentParent = child;
            lastCreated = child;
            return this;
        }

        public Builder terminal(String name) {
            var child = new StateNode(name, currentParent, true);
            currentParent.addChild(child);
            lastCreated = child;
            return this;
        }

        public Builder onEntry(Consumer<StateContext> action) {
            lastCreated.setEntryAction(action);
            return this;
        }

        public Builder onExit(Consumer<StateContext> action) {
            lastCreated.setExitAction(action);
            return this;
        }

        public Builder end() {
            if (currentParent == root) {
                throw new CartaException("INVALID_DEFINITION",
                    "end() called at root level — no open state() to close");
            }
            currentParent = currentParent.parent();
            lastCreated = currentParent;
            return this;
        }

        // ── Event transitions (Harel mode) ──────────────────

        public TransitionBuilder transition() { return new TransitionBuilder(this); }

        // ── Auto transitions (tramli mode) ──────────────────

        public Builder auto(String from, String to, StateProcessor processor) {
            transitions.add(new Transition(from, to, processor));
            return this;
        }

        // ── External transitions (tramli mode) ──────────────

        public Builder external(String from, String to, TransitionGuard guard) {
            transitions.add(new Transition(from, to, guard));
            return this;
        }

        // ── Branch transitions (tramli mode) ────────────────

        public Builder branch(String from, BranchProcessor processor, Map<String, String> targets) {
            transitions.add(new Transition(from, processor, targets));
            return this;
        }

        // ── Build ───────────────────────────────────────────

        public StateMachine build() {
            if (root == null) throw new CartaException("No root state defined");
            if (currentParent != root) {
                throw new CartaException("INVALID_DEFINITION",
                    "Unbalanced state nesting: missing end() for state '" +
                    currentParent.name() + "' (currentParent is not back at root '" +
                    root.name() + "' at build())");
            }
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
