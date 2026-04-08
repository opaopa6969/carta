package org.unlaxer;

import java.util.*;
import java.util.function.Consumer;

/**
 * Hierarchical state node. A state can have children (composite state)
 * or be a leaf. This is the core of Harel's Statechart — states nest.
 *
 * Entry/exit actions fire on every state entry/exit, regardless of
 * which transition caused it. This is Carta's declarative guarantee
 * that tramli lacks.
 */
public final class StateNode {
    private final String name;
    private final StateNode parent;
    private final List<StateNode> children = new ArrayList<>();
    private final boolean terminal;
    private boolean initial;
    private Consumer<StateContext> entryAction;
    private Consumer<StateContext> exitAction;

    StateNode(String name, StateNode parent, boolean terminal) {
        this.name = name;
        this.parent = parent;
        this.terminal = terminal;
    }

    public String name() { return name; }
    public StateNode parent() { return parent; }
    public List<StateNode> children() { return Collections.unmodifiableList(children); }
    public boolean isTerminal() { return terminal; }
    public boolean isInitial() { return initial; }
    public boolean isComposite() { return !children.isEmpty(); }
    public boolean isLeaf() { return children.isEmpty(); }

    void setInitial(boolean v) { this.initial = v; }
    void setEntryAction(Consumer<StateContext> a) { this.entryAction = a; }
    void setExitAction(Consumer<StateContext> a) { this.exitAction = a; }
    void addChild(StateNode child) { children.add(child); }

    public void executeEntry(StateContext ctx) {
        if (entryAction != null) entryAction.accept(ctx);
    }

    public void executeExit(StateContext ctx) {
        if (exitAction != null) exitAction.accept(ctx);
    }

    /** Full path from root: ["Order", "Processing", "PaymentPending"] */
    public List<String> path() {
        var path = new ArrayList<String>();
        for (StateNode n = this; n != null; n = n.parent) path.add(0, n.name);
        return path;
    }

    /** Find a descendant by name (BFS). */
    public Optional<StateNode> findDescendant(String name) {
        Queue<StateNode> q = new ArrayDeque<>(children);
        while (!q.isEmpty()) {
            StateNode n = q.poll();
            if (n.name.equals(name)) return Optional.of(n);
            q.addAll(n.children);
        }
        return Optional.empty();
    }

    /** The initial child state (for composite states). */
    public Optional<StateNode> initialChild() {
        return children.stream().filter(c -> c.initial).findFirst();
    }

    @Override public String toString() {
        return "State(" + String.join("/", path()) + (terminal ? ",terminal" : "") + ")";
    }
}
