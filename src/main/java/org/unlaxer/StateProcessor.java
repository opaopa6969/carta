package org.unlaxer;

import java.util.Set;

/**
 * Business logic for a single auto transition.
 *
 * Each processor declares what data it needs ({@link #requires()}) and
 * what it provides ({@link #produces()}). This contract enables build-time
 * data-flow verification — if a required type is never produced upstream,
 * {@code build()} fails before any code runs.
 *
 * <pre>
 * StateProcessor shipProcessor = new StateProcessor() {
 *     public Set&lt;Class&lt;?&gt;&gt; requires() { return Set.of(OrderId.class); }
 *     public Set&lt;Class&lt;?&gt;&gt; produces() { return Set.of(TrackingNumber.class); }
 *     public void process(StateContext ctx) {
 *         var orderId = ctx.get(OrderId.class);
 *         ctx.put(TrackingNumber.class, new TrackingNumber("TRACK-" + orderId.value()));
 *     }
 * };
 * </pre>
 */
public interface StateProcessor {

    /** Types this processor reads from context. Default: none. */
    default Set<Class<?>> requires() { return Set.of(); }

    /** Types this processor writes to context. Default: none. */
    default Set<Class<?>> produces() { return Set.of(); }

    /** Execute business logic, reading from and writing to context. */
    void process(StateContext ctx);
}
