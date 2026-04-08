package com.carta;

/**
 * Entry point for the Carta hierarchical state machine library.
 *
 * <pre>
 * var machine = Carta.define("Order")
 *     .root("Order")
 *     .initial("Created")
 *     .state("Processing")
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
}
