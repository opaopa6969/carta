package org.unlaxer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.*;
import java.util.stream.Stream;

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
    record MatrixPayload(String value) {}

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

    // ─── New tests ───────────────────────────────────────────

    @Test
    void resumeAlreadyCompletedReturnsAlreadyCompleted() {
        var engine = Carta.start(orderFlow());
        engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX", 100)));
        assertTrue(engine.isCompleted());
        var result = engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX2", 200)));
        assertEquals(CartaEngine.ResumeResult.ALREADY_COMPLETED, result);
    }

    @Test
    void resumeNoApplicableTransitionWhenNoneRegistered() {
        // A machine with no external transitions in initial state
        var noExternalFlow = Carta.define("NoExternal")
            .root("NoExternal")
                .initial("Waiting")
                .terminal("Done")
            .transition().from("Waiting").on(Event.of("go")).to("Done")
            .build();
        var engine = Carta.start(noExternalFlow);
        var result = engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("X", 1)));
        assertEquals(CartaEngine.ResumeResult.NO_APPLICABLE_TRANSITION, result);
    }

    @Test
    void dataFlowGraphDeadData() {
        // A machine where a processor produces a type that no one consumes
        record Orphan(String v) {}
        StateProcessor producer = new StateProcessor() {
            @Override public Set<Class<?>> produces() { return Set.of(Orphan.class); }
            @Override public void process(StateContext ctx) {
                ctx.put(Orphan.class, new Orphan("x"));
            }
        };
        var flow = Carta.define("DeadDataFlow")
            .root("DeadDataFlow")
                .initial("A")
                .terminal("B")
            .auto("A", "B", producer)
            .build();
        var graph = flow.dataFlowGraph();
        assertTrue(graph.deadData().contains(Orphan.class));
    }

    @Test
    void dataFlowGraphAllTypes() {
        var graph = orderFlow().dataFlowGraph();
        var allTypes = graph.allTypes();
        assertTrue(allTypes.contains(OrderId.class));
        assertTrue(allTypes.contains(PaymentConfirmation.class));
        assertTrue(allTypes.contains(TrackingNumber.class));
    }

    @Test
    void dataFlowGraphAvailableAt() {
        var graph = orderFlow().dataFlowGraph();
        // After PaymentPending, OrderId was produced by auto transition
        var atPaymentPending = graph.availableAt("PaymentPending");
        assertTrue(atPaymentPending.contains(OrderId.class));
    }

    @Test
    void dataFlowMarkdownContainsProducersAndConsumers() {
        var graph = orderFlow().dataFlowGraph();
        String md = graph.toMarkdown();
        assertTrue(md.contains("## Producers"));
        assertTrue(md.contains("## Consumers"));
        assertTrue(md.contains("## Availability"));
    }

    @Test
    void stateMachineNameAndTransitions() {
        var machine = orderFlow();
        assertEquals("OrderFlow", machine.name());
        assertFalse(machine.transitions().isEmpty());
    }

    @Test
    void branchBadLabelThrows() {
        BranchProcessor badDecider = new BranchProcessor() {
            @Override public Set<Class<?>> requires() { return Set.of(ShipmentReady.class); }
            @Override public String decide(StateContext ctx) { return "unknown_label"; }
        };
        var flow = Carta.define("BadBranch")
            .root("BadBranch")
                .initial("Pending")
                .state("Routing").end()
                .terminal("ExpressShipped")
                .terminal("StandardShipped")
            .auto("Pending", "Routing", new StateProcessor() {
                @Override public Set<Class<?>> produces() { return Set.of(ShipmentReady.class); }
                @Override public void process(StateContext ctx) {
                    ctx.put(ShipmentReady.class, new ShipmentReady(false));
                }
            })
            .branch("Routing", badDecider, Map.of(
                "express", "ExpressShipped",
                "standard", "StandardShipped"
            ))
            .build();
        var ex = assertThrows(CartaException.class, () -> Carta.start(flow));
        assertTrue(ex.getMessage().contains("unknown_label"));
        assertEquals("BRANCH_ERROR", ex.code());
    }

    @Test
    void compositeWithoutInitialChildFailsAtBuildTime() {
        var ex = assertThrows(CartaException.class, () ->
            Carta.define("MissingCompositeInitial")
                .root("MissingCompositeInitial")
                    .terminal("Done")
                .build()
        );

        assertEquals("INVALID_DEFINITION", ex.code());
        assertTrue(ex.getMessage().contains(
            "Composite state MissingCompositeInitial has no initial child"));
    }

    @Test
    void branchTargetMustExistAtBuildTime() {
        var ex = assertThrows(CartaException.class, () ->
            Carta.define("MissingBranchTarget")
                .root("MissingBranchTarget")
                    .initial("Routing")
                    .terminal("Known")
                .branch("Routing", ctx -> "missing", Map.of("missing", "Unknown"))
                .build()
        );

        assertEquals("INVALID_DEFINITION", ex.code());
        assertTrue(ex.getMessage().contains(
            "Branch label 'missing' maps to unknown state: Unknown"));
    }

    @Test
    void maxAutoChainDepthConstant() {
        assertEquals(10, CartaEngine.MAX_AUTO_CHAIN_DEPTH);
    }

    @Test
    void autoChainStopsWithoutExceptionAtMaximumDepth() {
        StateProcessor noop = ctx -> {};
        var builder = Carta.define("DepthBound")
            .root("DepthBound")
                .initial("S0");
        for (int i = 1; i <= CartaEngine.MAX_AUTO_CHAIN_DEPTH + 1; i++) {
            builder.state("S" + i).end();
        }
        for (int i = 0; i <= CartaEngine.MAX_AUTO_CHAIN_DEPTH; i++) {
            builder.auto("S" + i, "S" + (i + 1), noop);
        }

        var machine = builder.build();
        var engine = assertDoesNotThrow(() -> Carta.start(machine));

        assertEquals("S" + CartaEngine.MAX_AUTO_CHAIN_DEPTH,
            engine.currentState().name());
        assertEquals(CartaEngine.MAX_AUTO_CHAIN_DEPTH, engine.log().size());
        assertFalse(machine.autoTransitionsFrom(engine.currentState().name()).isEmpty(),
            "an eligible eleventh transition proves the chain stopped at the depth bound");
    }

    @Test
    void internalTransitionDoesNotRefireCompositeEntryOrExit() {
        int[] parentEntries = {0};
        int[] parentExits = {0};
        Event enter = Event.of("enter");
        Event internal = Event.of("internal");
        var flow = Carta.define("InternalLca")
            .root("InternalLca")
                .initial("Outside")
                .state("Group")
                    .onEntry(ctx -> parentEntries[0]++)
                    .onExit(ctx -> parentExits[0]++)
                    .initial("A")
                    .state("B").end()
                .end()
            .transition().from("Outside").on(enter).to("A")
            .transition().from("A").on(internal).to("B")
            .build();
        var engine = Carta.start(flow);

        assertTrue(engine.send(enter));
        assertEquals(1, parentEntries[0]);
        assertEquals(0, parentExits[0]);

        assertTrue(engine.send(internal));
        assertEquals("B", engine.currentState().name());
        assertEquals(1, parentEntries[0],
            "the LCA composite must not be entered again");
        assertEquals(0, parentExits[0],
            "the LCA composite must not be exited");
    }

    static Stream<Arguments> transitionAutoChainMatrix() {
        return Arrays.stream(Transition.Type.values())
            .flatMap(type -> Stream.of(false, true)
                .map(followOnAuto -> Arguments.of(type, followOnAuto)));
    }

    @ParameterizedTest(name = "{0}, follow-on auto-chain={1}")
    @MethodSource("transitionAutoChainMatrix")
    void allTransitionTypesWithAndWithoutFollowOnAutoChain(
            Transition.Type type, boolean followOnAuto) {
        Event event = Event.of("matrix-event");
        var builder = Carta.define("Matrix" + type + followOnAuto)
            .root("Matrix")
                .initial("Start")
                .state("Triggered").end()
                .terminal("Done");

        switch (type) {
            case EVENT -> builder.transition().from("Start").on(event).to("Triggered");
            case AUTO -> builder.auto("Start", "Triggered", ctx -> {});
            case EXTERNAL -> builder.external("Start", "Triggered", new TransitionGuard() {
                @Override public String name() { return "matrix-guard"; }
                @Override public Set<Class<?>> requires() { return Set.of(MatrixPayload.class); }
                @Override public GuardOutput evaluate(StateContext ctx) {
                    return GuardOutput.accepted();
                }
            });
            case BRANCH -> builder.branch(
                "Start", ctx -> "selected", Map.of("selected", "Triggered"));
        }
        if (followOnAuto) {
            builder.auto("Triggered", "Done", ctx -> {});
        }

        var engine = Carta.start(builder.build());
        switch (type) {
            case EVENT -> assertTrue(engine.send(event));
            case EXTERNAL -> assertEquals(CartaEngine.ResumeResult.TRANSITIONED,
                engine.resume(Map.of(MatrixPayload.class, new MatrixPayload("data"))));
            case AUTO, BRANCH -> { /* executed during engine construction */ }
        }

        assertEquals(followOnAuto ? "Done" : "Triggered",
            engine.currentState().name());
        assertEquals(followOnAuto ? 2 : 1, engine.log().size());
    }

    @Test
    void transitionLogRecordToString() {
        var engine = Carta.start(orderFlow());
        var record = engine.log().get(0);
        String str = record.toString();
        assertTrue(str.contains("Created"));
        assertTrue(str.contains("PaymentPending"));
        assertTrue(str.contains("[auto]"));
    }
}
