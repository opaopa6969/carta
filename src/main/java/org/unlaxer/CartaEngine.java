package org.unlaxer;

import java.util.*;

/**
 * Executes a {@link StateMachine} instance.
 *
 * Supports both Harel mode ({@link #send(Event)}) and tramli mode
 * ({@link #resume(Map)}), plus auto-chain execution that cascades
 * auto/branch transitions until an external wait or terminal state.
 *
 * Entry/exit semantics follow Harel's Statechart formalism:
 * <ul>
 *   <li>Exiting: exit actions fire from current leaf UP to LCA</li>
 *   <li>Entering: entry actions fire from LCA DOWN to target leaf</li>
 * </ul>
 *
 * Auto-chain depth is capped at {@value #MAX_AUTO_CHAIN_DEPTH} to
 * prevent infinite loops (build-time DAG check catches most cases).
 */
public final class CartaEngine {

    static final int MAX_AUTO_CHAIN_DEPTH = 10;

    private final StateMachine definition;
    private StateNode currentState;
    private final StateContext context;
    private final List<TransitionRecord> log = new ArrayList<>();
    private final Map<String, Integer> guardFailureCounts = new LinkedHashMap<>();

    public CartaEngine(StateMachine definition) {
        this(definition, new StateContext());
    }

    public CartaEngine(StateMachine definition, StateContext context) {
        this.definition = definition;
        this.context = context;
        this.currentState = definition.resolveToLeaf(definition.root());
        enterState(currentState);
        autoChain();
    }

    /** Restore from a FlowInstance (for long-lived flows). */
    public CartaEngine(StateMachine definition, FlowInstance instance) {
        this.definition = definition;
        this.context = instance.context();
        this.currentState = definition.state(instance.currentState());
        this.guardFailureCounts.putAll(extractGuardCounts(instance));
    }

    public StateMachine definition() { return definition; }
    public StateNode currentState() { return currentState; }
    public StateContext context() { return context; }
    public List<TransitionRecord> log() { return Collections.unmodifiableList(log); }
    public boolean isCompleted() { return currentState.isTerminal(); }

    /** Export current state as a FlowInstance. */
    public FlowInstance toFlowInstance(String id) {
        var instance = new FlowInstance(id, currentState.name(), context);
        return instance;
    }

    // ─── Harel Mode: Event-driven ──────────────────────────

    /**
     * Send an event to the state machine (Harel mode).
     * Fires auto-chain after transition.
     */
    public boolean send(Event event) {
        if (isCompleted()) return false;

        Transition match = findEventTransition(currentState, event);
        if (match == null) return false;

        StateNode from = currentState;
        StateNode to = definition.state(match.to());
        StateNode targetLeaf = definition.resolveToLeaf(to);
        StateNode lca = findLCA(from, to);

        exitUpTo(from, lca);
        match.execute(context);
        enterDownTo(targetLeaf, lca);

        boolean stateChanged = !from.name().equals(targetLeaf.name());
        currentState = targetLeaf;
        log.add(new TransitionRecord(from.name(), targetLeaf.name(), event.name()));

        if (stateChanged) guardFailureCounts.clear();
        autoChain();
        return true;
    }

    /** Send an event with string-keyed data (Harel convenience). */
    public boolean send(Event event, String key, Object value) {
        context.put(key, value);
        return send(event);
    }

    // ─── tramli Mode: External data-driven ─────────────────

    /**
     * Resume with typed external data (tramli mode).
     *
     * Finds matching external transitions by comparing
     * {@code externalData} keys against each guard's {@code requires()}.
     * Evaluates matching guards and transitions on first Accepted.
     * Fires auto-chain after transition.
     *
     * @return result indicating what happened
     */
    public ResumeResult resume(Map<Class<?>, Object> externalData) {
        if (isCompleted()) return ResumeResult.ALREADY_COMPLETED;

        context.putAllTyped(externalData);

        List<Transition> externals = findExternalTransitions(currentState);
        if (externals.isEmpty()) return ResumeResult.NO_APPLICABLE_TRANSITION;

        for (Transition t : externals) {
            TransitionGuard guard = t.transitionGuard();

            // DD-020: type-based routing — skip if required types not in data
            if (!externalData.keySet().containsAll(guard.requires())) continue;

            GuardOutput output = guard.evaluate(context);

            if (output instanceof GuardOutput.Accepted acc) {
                context.putAllTyped(acc.data());

                StateNode from = currentState;
                StateNode to = definition.state(t.to());
                StateNode targetLeaf = definition.resolveToLeaf(to);
                StateNode lca = findLCA(from, to);

                exitUpTo(from, lca);
                enterDownTo(targetLeaf, lca);

                boolean stateChanged = !from.name().equals(targetLeaf.name());
                currentState = targetLeaf;
                log.add(new TransitionRecord(from.name(), targetLeaf.name(), guard.name()));

                if (stateChanged) guardFailureCounts.clear();
                autoChain();
                return ResumeResult.TRANSITIONED;

            } else if (output instanceof GuardOutput.Rejected rej) {
                guardFailureCounts.merge(guard.name(), 1, Integer::sum);
            }
            // Expired: handled by caller
        }
        return ResumeResult.REJECTED;
    }

    /** Resume result for structured error handling. */
    public enum ResumeResult {
        TRANSITIONED,
        ALREADY_COMPLETED,
        NO_APPLICABLE_TRANSITION,
        REJECTED
    }

    // ─── Auto-chain ────────────────────────────────────────

    private void autoChain() {
        int depth = 0;
        while (depth < MAX_AUTO_CHAIN_DEPTH && !isCompleted()) {
            // Try auto transition first
            Transition auto = findAutoTransition(currentState);
            if (auto != null) {
                executeAutoTransition(auto);
                depth++;
                continue;
            }
            // Try branch transition
            Transition branch = findBranchTransition(currentState);
            if (branch != null) {
                executeBranchTransition(branch);
                depth++;
                continue;
            }
            break;
        }
    }

    private void executeAutoTransition(Transition t) {
        if (t.processor() != null) t.processor().process(context);

        StateNode from = currentState;
        StateNode to = definition.state(t.to());
        StateNode targetLeaf = definition.resolveToLeaf(to);
        StateNode lca = findLCA(from, to);

        exitUpTo(from, lca);
        enterDownTo(targetLeaf, lca);

        currentState = targetLeaf;
        log.add(new TransitionRecord(from.name(), targetLeaf.name(), "[auto]"));
    }

    private void executeBranchTransition(Transition t) {
        String label = t.branchProcessor().decide(context);
        String target = t.branchTargets().get(label);
        if (target == null) {
            throw new CartaException("BRANCH_ERROR",
                "Branch returned unknown label '" + label + "' from state " + t.from());
        }

        StateNode from = currentState;
        StateNode to = definition.state(target);
        StateNode targetLeaf = definition.resolveToLeaf(to);
        StateNode lca = findLCA(from, to);

        exitUpTo(from, lca);
        enterDownTo(targetLeaf, lca);

        currentState = targetLeaf;
        log.add(new TransitionRecord(from.name(), targetLeaf.name(), "[" + label + "]"));
    }

    // ─── Transition lookup ─────────────────────────────────

    private Transition findEventTransition(StateNode state, Event event) {
        for (StateNode s = state; s != null; s = s.parent()) {
            List<Transition> candidates = definition.transitionsFrom(s.name(), event);
            for (Transition t : candidates) {
                if (t.evaluate(context)) return t;
            }
        }
        return null;
    }

    private List<Transition> findExternalTransitions(StateNode state) {
        // Check current state and bubble to parents
        for (StateNode s = state; s != null; s = s.parent()) {
            List<Transition> ext = definition.externalTransitionsFrom(s.name());
            if (!ext.isEmpty()) return ext;
        }
        return List.of();
    }

    private Transition findAutoTransition(StateNode state) {
        List<Transition> autos = definition.autoTransitionsFrom(state.name());
        return autos.isEmpty() ? null : autos.getFirst();
    }

    private Transition findBranchTransition(StateNode state) {
        List<Transition> branches = definition.branchTransitionsFrom(state.name());
        return branches.isEmpty() ? null : branches.getFirst();
    }

    // ─── Entry/Exit (Harel semantics) ──────────────────────

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
        var path = new ArrayList<StateNode>();
        for (StateNode n = target; n != null && n != lca; n = n.parent()) {
            path.add(0, n);
        }
        for (StateNode n : path) {
            n.executeEntry(context);
        }
    }

    private void enterState(StateNode state) {
        var path = new ArrayList<StateNode>();
        for (StateNode n = state; n != null; n = n.parent()) path.add(0, n);
        for (StateNode n : path) n.executeEntry(context);
    }

    private Map<String, Integer> extractGuardCounts(FlowInstance instance) {
        // FlowInstance tracks guard failures internally; for engine restore
        // we start with clean counts. Long-lived flows persist via FlowStore.
        return Map.of();
    }

    // ─── Transition Record ──────────────────────────────────

    public record TransitionRecord(String from, String to, String trigger) {
        @Override public String toString() {
            return from + " --[" + trigger + "]--> " + to;
        }
    }
}
