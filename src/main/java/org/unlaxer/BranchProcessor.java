package org.unlaxer;

import java.util.Set;

/**
 * Conditional routing processor for branch transitions.
 *
 * Returns a label that maps to the next state. All possible labels
 * must be declared in the branch transition's target map; the engine
 * validates this at build time.
 *
 * <pre>
 * BranchProcessor paymentRouter = new BranchProcessor() {
 *     public Set&lt;Class&lt;?&gt;&gt; requires() { return Set.of(PaymentResult.class); }
 *     public String decide(StateContext ctx) {
 *         return ctx.get(PaymentResult.class).approved() ? "approved" : "rejected";
 *     }
 * };
 * </pre>
 */
public interface BranchProcessor {

    /** Types this processor reads from context. Default: none. */
    default Set<Class<?>> requires() { return Set.of(); }

    /** Return a label that maps to the next state. */
    String decide(StateContext ctx);
}
