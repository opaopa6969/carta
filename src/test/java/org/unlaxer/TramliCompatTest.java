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

    /**
     * #34: When resume() is rejected by a guard, the external data passed to
     * that call must not linger in the context. A previously-absent type must
     * remain absent after a rejection.
     */
    @Test
    void resumeRejectedRollsBackExternalData() {
        var engine = Carta.start(orderFlow());
        assertEquals("PaymentPending", engine.currentState().name());
        assertFalse(engine.context().has(PaymentConfirmation.class));

        var result = engine.resume(Map.of(
            PaymentConfirmation.class, new PaymentConfirmation("TX-BAD", 0)
        ));

        assertEquals(CartaEngine.ResumeResult.REJECTED, result);
        assertEquals("PaymentPending", engine.currentState().name());
        assertFalse(engine.context().has(PaymentConfirmation.class),
            "rejected external data must not linger in context");
    }

    /**
     * #34: A type that was already present in the context before resume() must
     * be restored to its prior value when resume() is rejected, so a rejected
     * retry cannot clobber a previously accepted value.
     */
    @Test
    void resumeRejectedRestoresPriorValue() {
        var engine = Carta.start(orderFlow());
        assertEquals("PaymentPending", engine.currentState().name());
        // Seed context with a prior PaymentConfirmation (e.g. from a prior step)
        engine.context().put(PaymentConfirmation.class, new PaymentConfirmation("PRIOR", 1));

        var result = engine.resume(Map.of(
            PaymentConfirmation.class, new PaymentConfirmation("TX-BAD", 0)
        ));

        assertEquals(CartaEngine.ResumeResult.REJECTED, result);
        assertTrue(engine.context().has(PaymentConfirmation.class));
        assertEquals("PRIOR", engine.context().get(PaymentConfirmation.class).txId(),
            "prior typed value must be restored after rejection");
    }

    /**
     * #34: GuardOutput.Expired must also roll back external data.
     */
    @Test
    void resumeExpiredRollsBackExternalData() {
        TransitionGuard expGuard = new TransitionGuard() {
            @Override public String name() { return "exp"; }
            @Override public Set<Class<?>> requires() { return Set.of(PaymentConfirmation.class); }
            @Override public GuardOutput evaluate(StateContext ctx) {
                return GuardOutput.expired();
            }
        };
        var machine = Carta.define("ExpRollback")
            .root("ExpRollback")
                .initial("Pending")
                .terminal("Done")
            .external("Pending", "Done", expGuard)
            .build();
        var engine = Carta.start(machine);

        var result = engine.resume(Map.of(
            PaymentConfirmation.class, new PaymentConfirmation("X", 0)
        ));

        assertEquals(CartaEngine.ResumeResult.EXPIRED, result);
        assertEquals("Pending", engine.currentState().name());
        assertFalse(engine.context().has(PaymentConfirmation.class),
            "expired external data must not linger in context");
    }

    /**
     * #34: Accepted resume still leaves the external data in the context
     * (regression guard against over-aggressive rollback on the happy path).
     */
    @Test
    void resumeAcceptedKeepsExternalData() {
        var engine = Carta.start(orderFlow());
        assertEquals("PaymentPending", engine.currentState().name());

        var result = engine.resume(Map.of(
            PaymentConfirmation.class, new PaymentConfirmation("TX-OK", 1000)
        ));

        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        assertTrue(engine.context().has(PaymentConfirmation.class),
            "accepted external data must remain in context");
        assertEquals("TX-OK", engine.context().get(PaymentConfirmation.class).txId());
    }

    /**
     * Guard failure counts must survive export → FlowStore → restore round-trip
     * so that N-strike rules (e.g. ban after N rejections) survive long-lived flows.
     * Regression for #7: previously extractGuardCounts() returned an empty map and
     * toFlowInstance() did not transfer counts, so counts silently reset on restore.
     */
    @Test
    void guardFailureCountsPersistAcrossExportRestore() {
        var engine = Carta.start(orderFlow());
        assertEquals("PaymentPending", engine.currentState().name());

        // Accumulate two guard rejections for paymentGuard
        engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX-1", 0)));
        engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX-2", 0)));

        // Export to FlowInstance — counts must transfer
        FlowInstance instance = engine.toFlowInstance("order-persist-1");
        assertEquals(2, instance.guardFailureCounts().getOrDefault("paymentGuard", 0));

        // Persist and reload via FlowStore
        var store = Carta.memoryStore();
        store.save(instance);
        FlowInstance loaded = store.load("order-persist-1").orElseThrow();
        assertEquals(2, loaded.guardFailureCounts().getOrDefault("paymentGuard", 0));

        // Restore into a fresh engine — counts must come back
        var restored = Carta.restore(orderFlow(), loaded);
        // A third rejection after restore should advance the count to 3, not reset to 1
        var result = restored.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX-3", 0)));
        assertEquals(CartaEngine.ResumeResult.REJECTED, result);
        FlowInstance after = restored.toFlowInstance("order-persist-2");
        assertEquals(3, after.guardFailureCounts().getOrDefault("paymentGuard", 0));
    }

    /**
     * A successful transition clears guard failure counts (existing contract),
     * and that clear must also be reflected when exported after the transition.
     */
    @Test
    void guardFailureCountsClearedOnStateChange() {
        var engine = Carta.start(orderFlow());
        // One rejection
        engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX-BAD", 0)));
        FlowInstance before = engine.toFlowInstance("pre");
        assertEquals(1, before.guardFailureCounts().getOrDefault("paymentGuard", 0));

        // Successful transition clears counts
        engine.resume(Map.of(PaymentConfirmation.class, new PaymentConfirmation("TX-OK", 1000)));
        assertEquals("Shipped", engine.currentState().name());
        FlowInstance after = engine.toFlowInstance("post");
        assertEquals(0, after.guardFailureCounts().getOrDefault("paymentGuard", 0));
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
    void branchNullLabelThrowsCartaException() {
        BranchProcessor nullDecider = new BranchProcessor() {
            @Override public Set<Class<?>> requires() { return Set.of(ShipmentReady.class); }
            @Override public String decide(StateContext ctx) { return null; }
        };
        var flow = Carta.define("NullBranch")
            .root("NullBranch")
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
            .branch("Routing", nullDecider, Map.of(
                "express", "ExpressShipped",
                "standard", "StandardShipped"
            ))
            .build();
        var ex = assertThrows(CartaException.class, () -> Carta.start(flow));
        assertEquals("BRANCH_ERROR", ex.code());
        assertTrue(ex.getMessage().contains("null"));
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

    /**
     * #33 case 1: onEntry/onExit immediately after initial()/terminal() must
     * attach to that state, not to the current parent (root).
     */
    @Test
    void onEntryAfterInitialAttachesToThatState() {
        List<String> order = new ArrayList<>();
        var machine = Carta.define("Repro")
            .root("Repro")
                .onEntry(ctx -> order.add("root"))
                .initial("A")
                .onEntry(ctx -> order.add("second"))
                .terminal("B")
            .transition().from("A").on(Event.of("go")).to("B")
            .build();
        Carta.start(machine);
        // Both root and A entry actions should fire on entry into A.
        assertTrue(order.contains("root"));
        assertTrue(order.contains("second"),
            "onEntry after initial() must attach to that initial state, not overwrite root");
        // Order: root first (entering root), then A (entering A as initial child).
        int rootIdx = order.indexOf("root");
        int secondIdx = order.indexOf("second");
        assertTrue(rootIdx < secondIdx, "root entry should fire before A entry");
    }

    /**
     * #33 case 3: duplicate initial() within the same composite must fail build().
     */
    @Test
    void duplicateInitialChildFailsAtBuildTime() {
        var ex = assertThrows(CartaException.class, () ->
            Carta.define("Repro3")
                .root("Repro3")
                    .initial("First")
                    .initial("Second")
                    .terminal("Done")
                .build()
        );

        assertEquals("INVALID_DEFINITION", ex.code());
        assertTrue(ex.getMessage().contains("Repro3"));
        assertTrue(ex.getMessage().contains("initial children"));
    }

    /**
     * #33 case 4: missing end() leaves currentParent below root at build(),
     * which must be detected and rejected.
     */
    @Test
    void missingEndFailsAtBuildTime() {
        var ex = assertThrows(CartaException.class, () ->
            Carta.define("Repro4")
                .root("Repro4")
                    .initial("Created")
                    .state("Processing")
                        .initial("PaymentPending")
                    .terminal("Cancelled")
                .transition().from("Created").on(Event.of("start")).to("PaymentPending")
                .transition().from("Processing").on(Event.of("cancel")).to("Cancelled")
                .build()
        );

        assertEquals("INVALID_DEFINITION", ex.code());
        assertTrue(ex.getMessage().contains("missing end()"));
        assertTrue(ex.getMessage().contains("Processing"));
    }

    /**
     * #33: excess end() (calling end() at root level with no open state()) must
     * be rejected rather than silently no-op'ing.
     */
    @Test
    void excessEndFailsAtBuildTime() {
        var ex = assertThrows(CartaException.class, () ->
            Carta.define("ExcessEnd")
                .root("ExcessEnd")
                    .initial("A")
                    .terminal("B")
                .end()
                .build()
        );

        assertEquals("INVALID_DEFINITION", ex.code());
        assertTrue(ex.getMessage().contains("end()"));
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

    // ─── #5 requires/produces whole-definition semantics ──────

    /**
     * #5: build() verifies whole-definition producer existence, NOT path
     * reachability. A type produced only on one branch that the current path
     * never takes still passes build() — the guard's requires() is satisfied
     * because a producer exists somewhere in the definition.
     */
    @Test
    void dataFlowValidationIsWholeDefinitionNotPathReachability() {
        record OnlyOnBranch(String v) {}
        record NeededByGuard(String v) {}

        // branchA produces OnlyOnBranch; branchB does NOT, but needs it via guard.
        // Whole-definition check: OnlyOnBranch is produced (on branchA) → build passes.
        // Path-reachability check would reject: branchB can't see branchA's output.
        StateProcessor branchAProducer = new StateProcessor() {
            @Override public Set<Class<?>> produces() { return Set.of(OnlyOnBranch.class); }
            @Override public void process(StateContext ctx) {
                ctx.put(OnlyOnBranch.class, new OnlyOnBranch("a"));
            }
        };
        TransitionGuard guardNeedingOnlyOnBranch = new TransitionGuard() {
            @Override public String name() { return "needsOnlyOnBranch"; }
            @Override public Set<Class<?>> requires() { return Set.of(OnlyOnBranch.class); }
            @Override public GuardOutput evaluate(StateContext ctx) {
                return GuardOutput.accepted();
            }
        };

        var machine = assertDoesNotThrow(() ->
            Carta.define("PathDivergence")
                .root("PathDivergence")
                    .initial("Start")
                    .state("BranchA").end()
                    .state("BranchB").end()
                    .terminal("End")
                .auto("Start", "BranchA", branchAProducer)
                .external("BranchB", "End", guardNeedingOnlyOnBranch)
                .build()
        );
        // build() passed: OnlyOnBranch has a producer (on BranchA), satisfying
        // the whole-definition check even though BranchB can never reach BranchA.
        // This pins the current semantics so a future path-aware change must
        // update this test deliberately.
        assertNotNull(machine);
    }

    /**
     * #5: the README and spec now state that build() does a whole-definition
     * existence check, not path-based verification. This test pins the wording
     * so a future README drift is caught.
     */
    @Test
    void readmeDocumentsWholeDefinitionNotPathSemantics() throws Exception {
        var readme = java.nio.file.Files.readString(
            java.nio.file.Path.of("README.md"));
        // The corrected wording must NOT claim "upstream" path verification.
        assertFalse(readme.contains("produced by some processor upstream"),
            "README must not claim path-based 'upstream' verification");
        // It must state the whole-definition existence check.
        assertTrue(readme.contains("whole-definition existence check"),
            "README must document the whole-definition existence check");
    }
}
