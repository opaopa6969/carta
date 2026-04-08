package org.unlaxer;

import java.util.Set;

/**
 * Guard for external transitions. Evaluates whether an external event
 * should trigger a state transition.
 *
 * Unlike Harel-style guards (pure boolean predicates), TransitionGuard:
 * <ul>
 *   <li>Returns structured {@link GuardOutput} (Accepted/Rejected/Expired)</li>
 *   <li>Declares {@link #requires()} for type-based routing (DD-020)</li>
 *   <li>Has a unique {@link #name()} for guard-failure tracking</li>
 * </ul>
 *
 * When multiple external transitions share a source state, the engine
 * selects guards by matching {@code requires()} types against the
 * external data provided to {@code resume()}.
 */
public interface TransitionGuard {

    /** Unique name within a source state. Used for failure-count tracking. */
    String name();

    /** Types this guard needs in context/external-data for routing. Default: none. */
    default Set<Class<?>> requires() { return Set.of(); }

    /** Evaluate the guard. Context already contains external data. */
    GuardOutput evaluate(StateContext ctx);
}
