package org.unlaxer;

/**
 * Entry point for the Carta state machine library.
 *
 * Carta unifies Harel's Statechart expressiveness (hierarchical states,
 * entry/exit actions, event bubbling) with tramli's data-flow verification
 * (requires/produces contracts, auto-chain, build-time validation).
 *
 * <h3>Harel mode — hierarchical, event-driven:</h3>
 * <pre>
 * var machine = Carta.define("Order")
 *     .root("Order")
 *     .initial("Created")
 *     .state("Processing")
 *         .onEntry(ctx -&gt; ctx.put("processing", true))
 *         .initial("PaymentPending")
 *         .state("Confirmed").end()
 *         .terminal("Shipped")
 *     .end()
 *     .terminal("Cancelled")
 *     .transition().from("Created").on(start).to("PaymentPending")
 *     .build();
 *
 * var engine = Carta.start(machine);
 * engine.send(paymentReceived);
 * </pre>
 *
 * <h3>tramli mode — flat, data-flow verified:</h3>
 * <pre>
 * var machine = Carta.define("Order")
 *     .root("Order")
 *     .initial("Created")
 *     .state("PaymentPending").end()
 *     .state("Confirmed").end()
 *     .terminal("Shipped")
 *     .auto("Created", "PaymentPending", initProcessor)
 *     .external("PaymentPending", "Confirmed", paymentGuard)
 *     .auto("Confirmed", "Shipped", shipProcessor)
 *     .build();
 *
 * var engine = Carta.start(machine);
 * // auto-chain fires: Created → PaymentPending (stops, waiting for external)
 * engine.resume(Map.of(PaymentConfirmation.class, confirmation));
 * // auto-chain fires: PaymentPending → Confirmed → Shipped
 * </pre>
 */
public final class Carta {

    private Carta() {}

    /** Define a new state machine. */
    public static StateMachine.Builder define(String name) {
        return StateMachine.builder(name);
    }

    /** Create and start an engine for the given state machine. */
    public static CartaEngine start(StateMachine machine) {
        return new CartaEngine(machine);
    }

    /** Create and start with initial context. */
    public static CartaEngine start(StateMachine machine, StateContext ctx) {
        return new CartaEngine(machine, ctx);
    }

    /** Restore an engine from a persisted FlowInstance. */
    public static CartaEngine restore(StateMachine machine, FlowInstance instance) {
        return new CartaEngine(machine, instance);
    }

    /** Create a new in-memory FlowStore. */
    public static InMemoryFlowStore memoryStore() {
        return new InMemoryFlowStore();
    }
}
