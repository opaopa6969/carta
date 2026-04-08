package com.carta;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrderFlowTest {

    static final Event START = Event.of("Start");
    static final Event PAYMENT = Event.of("PaymentReceived");
    static final Event SHIP = Event.of("Ship");
    static final Event CANCEL = Event.of("Cancel");

    StateMachine orderMachine() {
        return Carta.define("Order")
            .root("Order")
                .initial("Created")
                .state("Processing")
                    .onEntry(ctx -> ctx.put("processing", true))
                    .onExit(ctx -> ctx.put("processing", false))
                    .initial("PaymentPending")
                    .state("Confirmed").end()
                    .terminal("Shipped")
                .end()
                .terminal("Cancelled")
            .transition().from("Created").on(START).to("PaymentPending")
            .transition().from("PaymentPending").on(PAYMENT)
                .guard(ctx -> ctx.find("amount", Integer.class).map(a -> a > 0).orElse(false))
                .action(ctx -> ctx.put("confirmed", true))
                .to("Confirmed")
            .transition().from("Confirmed").on(SHIP)
                .action(ctx -> ctx.put("tracking", "TRACK-001"))
                .to("Shipped")
            .transition().from("Processing").on(CANCEL).to("Cancelled")
            .build();
    }

    @Test
    void happyPath() {
        var engine = Carta.start(orderMachine());
        assertEquals("Created", engine.currentState().name());

        engine.send(START);
        assertEquals("PaymentPending", engine.currentState().name());
        // Entry action of Processing should have fired
        assertEquals(true, engine.context().get("processing", Boolean.class));

        engine.send(PAYMENT, "amount", 1000);
        assertEquals("Confirmed", engine.currentState().name());
        assertEquals(true, engine.context().get("confirmed", Boolean.class));

        engine.send(SHIP);
        assertEquals("Shipped", engine.currentState().name());
        assertTrue(engine.isCompleted());
        assertEquals("TRACK-001", engine.context().get("tracking", String.class));
        // Exit action of Processing should have fired
        assertEquals(false, engine.context().get("processing", Boolean.class));
    }

    @Test
    void guardRejectsInvalidPayment() {
        var engine = Carta.start(orderMachine());
        engine.send(START);
        // No amount set → guard fails
        boolean took = engine.send(PAYMENT);
        assertFalse(took);
        assertEquals("PaymentPending", engine.currentState().name());
    }

    @Test
    void cancelFromProcessing() {
        var engine = Carta.start(orderMachine());
        engine.send(START);
        engine.send(PAYMENT, "amount", 500);
        assertEquals("Confirmed", engine.currentState().name());

        // Cancel is on Processing (parent) → should work from any child
        engine.send(CANCEL);
        assertEquals("Cancelled", engine.currentState().name());
        assertTrue(engine.isCompleted());
    }

    @Test
    void hierarchicalEventBubbling() {
        // Cancel event is defined on Processing, not on PaymentPending.
        // It should still fire when in PaymentPending (child bubbles to parent).
        var engine = Carta.start(orderMachine());
        engine.send(START);
        assertEquals("PaymentPending", engine.currentState().name());

        engine.send(CANCEL);
        assertEquals("Cancelled", engine.currentState().name());
    }

    @Test
    void entryExitActions() {
        var engine = Carta.start(orderMachine());
        assertFalse(engine.context().has("processing"));

        engine.send(START);
        assertEquals(true, engine.context().get("processing", Boolean.class));

        engine.send(PAYMENT, "amount", 100);
        engine.send(SHIP);
        // Exited Processing → exit action should set processing=false
        assertEquals(false, engine.context().get("processing", Boolean.class));
    }

    @Test
    void transitionLog() {
        var engine = Carta.start(orderMachine());
        engine.send(START);
        engine.send(PAYMENT, "amount", 100);
        engine.send(SHIP);

        assertEquals(3, engine.log().size());
        assertEquals("Created", engine.log().get(0).from());
        assertEquals("Shipped", engine.log().get(2).to());
    }

    @Test
    void mermaidGeneration() {
        var mermaid = orderMachine().toMermaid();
        assertTrue(mermaid.contains("stateDiagram-v2"));
        assertTrue(mermaid.contains("Processing"));
        assertTrue(mermaid.contains("Shipped --> [*]"));
    }
}
