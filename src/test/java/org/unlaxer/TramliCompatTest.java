package org.unlaxer;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests tramli-equivalent features in Carta:
 * auto transitions, auto-chain, requires/produces,
 * branch routing, type-safe context, data-flow verification.
 */
class TramliCompatTest {

    // ─── Domain types for requires/produces ────────────────

    record OrderId(String value) {}
    record PaymentConfirmation(String txId, int amount) {}
    record TrackingNumber(String value) {}
    record ShipmentReady(boolean express) {}

    // ─── Processors ────────────────────────────────────────

    static final StateProcessor initProcessor = new StateProcessor() {
        @Override public Set<Class<?>> produces() { return Set.of(OrderId.class); }
        @Override public void process(StateContext ctx) {
            ctx.put(OrderId.class, new OrderId("ORD-001"));
        }
    };

    static final StateProcessor shipProcessor = new StateProcessor() {
        @Override public Set<Class<?>> requires() { return Set.of(OrderId.class); }
        @Override public Set<Class<?>> produces() { return Set.of(TrackingNumber.class); }
        @Override public void process(StateContext ctx) {
            var orderId = ctx.get(OrderId.class);
            ctx.put(TrackingNumber.class, new TrackingNumber("TRACK-" + orderId.value()));
        }
    };

    // ─── Guard ─────────────────────────────────────────────

    static final TransitionGuard paymentGuard = new TransitionGuard() {
        @Override public String name() { return "paymentGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(PaymentConfirmation.class); }
        @Override public GuardOutput evaluate(StateContext ctx) {
            var pc = ctx.get(PaymentConfirmation.class);
            if (pc.amount() > 0) {
                return GuardOutput.accepted();
            }
            return GuardOutput.rejected("Invalid amount");
        }
    };

    // ─── Branch ────────────────────────────────────────────

    static final BranchProcessor shippingRouter = new BranchProcessor() {
        @Override public Set<Class<?>> requires() { return Set.of(ShipmentReady.class); }
        @Override public String decide(StateContext ctx) {
            return ctx.get(ShipmentReady.class).express() ? "express" : "standard";
        }
    };

    // ─── Tests ─────────────────────────────────────────────

    StateMachine orderFlow() {
        return Carta.define("OrderFlow")
            .root("OrderFlow")
                .initial("Created")
                .state("PaymentPending").end()
                .state("Confirmed").end()
                .terminal("Shipped")
                .terminal("Cancelled")
            .auto("Created", "PaymentPending", initProcessor)
            .external("PaymentPending", "Confirmed", paymentGuard)
            .auto("Confirmed", "Shipped", shipProcessor)
            .build();
    }

    @Test
    void autoChainFromInitial() {
        var engine = Carta.start(orderFlow());
        // Auto-chain: Created → PaymentPending (stops, waiting for external)
        assertEquals("PaymentPending", engine.currentState().name());
        // initProcessor should have produced OrderId
        assertEquals("ORD-001", engine.context().get(OrderId.class).value());
    }

    @Test
    void resumeWithExternalData() {
        var engine = Carta.start(orderFlow());
        assertEquals("PaymentPending", engine.currentState().name());

        // Resume with payment confirmation
        var result = engine.resume(Map.of(
            PaymentConfirmation.class, new PaymentConfirmation("TX-001", 1000)
        ));

        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        // Auto-chain: PaymentPending → Confirmed → Shipped
        assertEquals("Shipped", engine.currentState().name());
        assertTrue(engine.isCompleted());

        // shipProcessor should have produced TrackingNumber
        assertEquals("TRACK-ORD-001", engine.context().get(TrackingNumber.class).value());
    }

    @Test
    void resumeRejectedByGuard() {
        var engine = Carta.start(orderFlow());
        assertEquals("PaymentPending", engine.currentState().name());

        // Invalid payment
        var result = engine.resume(Map.of(
            PaymentConfirmation.class, new PaymentConfirmation("TX-BAD", 0)
        ));

        assertEquals(CartaEngine.ResumeResult.REJECTED, result);
        assertEquals("PaymentPending", engine.currentState().name());
    }

    @Test
    void branchTransition() {
        var flow = Carta.define("ShipFlow")
            .root("ShipFlow")
                .initial("Pending")
                .state("Routing").end()
                .terminal("ExpressShipped")
                .terminal("StandardShipped")
            .auto("Pending", "Routing", new StateProcessor() {
                @Override public Set<Class<?>> produces() { return Set.of(ShipmentReady.class); }
                @Override public void process(StateContext ctx) {
                    ctx.put(ShipmentReady.class, new ShipmentReady(true));
                }
            })
            .branch("Routing", shippingRouter, Map.of(
                "express", "ExpressShipped",
                "standard", "StandardShipped"
            ))
            .build();

        var engine = Carta.start(flow);
        // Auto-chain: Pending → Routing → ExpressShipped
        assertEquals("ExpressShipped", engine.currentState().name());
        assertTrue(engine.isCompleted());
    }

    @Test
    void typeSafeContext() {
        var engine = Carta.start(orderFlow());
        // Type-safe access
        OrderId orderId = engine.context().get(OrderId.class);
        assertNotNull(orderId);
        assertEquals("ORD-001", orderId.value());

        // Optional access
        assertTrue(engine.context().find(OrderId.class).isPresent());
        assertFalse(engine.context().find(TrackingNumber.class).isPresent());

        // Available types
        assertTrue(engine.context().availableTypes().contains(OrderId.class));
    }

    @Test
    void dataFlowVerificationRejectsMissingProducer() {
        // Processor requires a type that nobody produces
        StateProcessor badProcessor = new StateProcessor() {
            @Override public Set<Class<?>> requires() { return Set.of(TrackingNumber.class); }
            @Override public void process(StateContext ctx) {}
        };

        var ex = assertThrows(CartaException.class, () ->
            Carta.define("BadFlow")
                .root("BadFlow")
                    .initial("A")
                    .terminal("B")
                .auto("A", "B", badProcessor)
                .build()
        );

        assertTrue(ex.getMessage().contains("TrackingNumber"));
        assertTrue(ex.getMessage().contains("never produced"));
    }

    @Test
    void autoDAGCycleDetected() {
        StateProcessor noop = new StateProcessor() {
            @Override public void process(StateContext ctx) {}
        };

        var ex = assertThrows(CartaException.class, () ->
            Carta.define("CycleFlow")
                .root("CycleFlow")
                    .initial("A")
                    .state("B").end()
                    .terminal("C")
                .auto("A", "B", noop)
                .auto("B", "A", noop)
                .build()
        );

        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void duplicateGuardNameDetected() {
        TransitionGuard g1 = new TransitionGuard() {
            @Override public String name() { return "sameGuard"; }
            @Override public GuardOutput evaluate(StateContext ctx) { return GuardOutput.accepted(); }
        };
        TransitionGuard g2 = new TransitionGuard() {
            @Override public String name() { return "sameGuard"; }
            @Override public GuardOutput evaluate(StateContext ctx) { return GuardOutput.accepted(); }
        };

        var ex = assertThrows(CartaException.class, () ->
            Carta.define("DuplicateGuard")
                .root("DuplicateGuard")
                    .initial("A")
                    .state("B").end()
                    .terminal("C")
                .external("A", "B", g1)
                .external("A", "C", g2)
                .build()
        );

        assertTrue(ex.getMessage().contains("Duplicate guard name"));
    }

    @Test
    void transitionLog() {
        var engine = Carta.start(orderFlow());
        assertEquals(1, engine.log().size());  // auto: Created → PaymentPending

        engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX-1", 500)));
        // +1 external, +1 auto = 3 total
        assertEquals(3, engine.log().size());
        assertEquals("Created", engine.log().get(0).from());
        assertEquals("Shipped", engine.log().get(2).to());
    }

    @Test
    void mermaidIncludesAllTransitionTypes() {
        var mermaid = orderFlow().toMermaid();
        assertTrue(mermaid.contains("[auto]"));
        assertTrue(mermaid.contains("paymentGuard"));
    }

    @Test
    void dataFlowGraph() {
        var graph = orderFlow().dataFlowGraph();

        // OrderId produced at PaymentPending, consumed at Confirmed
        assertFalse(graph.producersOf(OrderId.class).isEmpty());
        assertFalse(graph.consumersOf(OrderId.class).isEmpty());

        // Markdown report
        String md = graph.toMarkdown();
        assertTrue(md.contains("OrderId"));
        assertTrue(md.contains("TrackingNumber"));
    }
}
