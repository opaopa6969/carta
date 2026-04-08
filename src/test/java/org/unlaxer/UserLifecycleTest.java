package org.unlaxer;

import org.junit.jupiter.api.Test;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Multi-external transitions for long-lived flows (DD-020).
 *
 * Demonstrates the user lifecycle pattern from the DGE session:
 * ACTIVE state with multiple external transitions, each routed by
 * requires() type matching.
 */
class UserLifecycleTest {

    // ─── Domain types ──────────────────────────────────────

    record UserId(String value) {}
    record ProfileUpdate(String name) {}
    record SuspendRequest(String reason) {}
    record BanOrder(String reason, String issuedBy) {}
    record ReactivateRequest(String approvedBy) {}

    // ─── Processors ────────────────────────────────────────

    static final StateProcessor registrationProcessor = new StateProcessor() {
        @Override public Set<Class<?>> produces() { return Set.of(UserId.class); }
        @Override public void process(StateContext ctx) {
            ctx.put(UserId.class, new UserId("USR-" + System.nanoTime()));
        }
    };

    // ─── Guards ────────────────────────────────────────────

    static final TransitionGuard verifyGuard = new TransitionGuard() {
        @Override public String name() { return "verifyGuard"; }
        @Override public GuardOutput evaluate(StateContext ctx) {
            return GuardOutput.accepted();
        }
    };

    static final TransitionGuard profileUpdateGuard = new TransitionGuard() {
        @Override public String name() { return "profileUpdateGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(ProfileUpdate.class); }
        @Override public GuardOutput evaluate(StateContext ctx) {
            return GuardOutput.accepted();
        }
    };

    static final TransitionGuard suspendGuard = new TransitionGuard() {
        @Override public String name() { return "suspendGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(SuspendRequest.class); }
        @Override public GuardOutput evaluate(StateContext ctx) {
            return GuardOutput.accepted();
        }
    };

    static final TransitionGuard banGuard = new TransitionGuard() {
        @Override public String name() { return "banGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(BanOrder.class); }
        @Override public GuardOutput evaluate(StateContext ctx) {
            return GuardOutput.accepted();
        }
    };

    static final TransitionGuard reactivateGuard = new TransitionGuard() {
        @Override public String name() { return "reactivateGuard"; }
        @Override public Set<Class<?>> requires() { return Set.of(ReactivateRequest.class); }
        @Override public GuardOutput evaluate(StateContext ctx) {
            return GuardOutput.accepted();
        }
    };

    // ─── Flow Definition ───────────────────────────────────

    StateMachine userLifecycle() {
        return Carta.define("UserLifecycle")
            .root("UserLifecycle")
                .initial("Registered")
                .state("Verified").end()
                .state("Active").end()
                .state("Suspended").end()
                .terminal("Banned")
                .terminal("Deleted")
            .auto("Registered", "Verified", registrationProcessor)
            .external("Verified", "Active", verifyGuard)
            // Multi-external from Active (DD-020)
            .external("Active", "Active", profileUpdateGuard)     // self-transition
            .external("Active", "Suspended", suspendGuard)
            .external("Active", "Banned", banGuard)
            // Reactivation from Suspended
            .external("Suspended", "Active", reactivateGuard)
            .build();
    }

    // ─── Tests ─────────────────────────────────────────────

    @Test
    void registrationAutoChain() {
        var engine = Carta.start(userLifecycle());
        // Auto: Registered → Verified (stops, waiting for external)
        assertEquals("Verified", engine.currentState().name());
        assertNotNull(engine.context().get(UserId.class));
    }

    @Test
    void verifyAndActivate() {
        var engine = Carta.start(userLifecycle());
        assertEquals("Verified", engine.currentState().name());

        var result = engine.resume(Map.of());
        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        assertEquals("Active", engine.currentState().name());
    }

    @Test
    void selfTransitionProfileUpdate() {
        var engine = Carta.start(userLifecycle());
        engine.resume(Map.of());  // → Active
        assertEquals("Active", engine.currentState().name());

        // Self-transition: Active → Active
        var result = engine.resume(Map.of(
            ProfileUpdate.class, new ProfileUpdate("New Name")
        ));
        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        assertEquals("Active", engine.currentState().name());  // still Active
    }

    @Test
    void suspendFromActive() {
        var engine = Carta.start(userLifecycle());
        engine.resume(Map.of());  // → Active

        var result = engine.resume(Map.of(
            SuspendRequest.class, new SuspendRequest("Violation")
        ));
        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        assertEquals("Suspended", engine.currentState().name());
    }

    @Test
    void banFromActive() {
        var engine = Carta.start(userLifecycle());
        engine.resume(Map.of());  // → Active

        var result = engine.resume(Map.of(
            BanOrder.class, new BanOrder("Fraud", "admin")
        ));
        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        assertEquals("Banned", engine.currentState().name());
        assertTrue(engine.isCompleted());
    }

    @Test
    void suspendAndReactivate() {
        var engine = Carta.start(userLifecycle());
        engine.resume(Map.of());  // → Active

        engine.resume(Map.of(SuspendRequest.class, new SuspendRequest("Review")));
        assertEquals("Suspended", engine.currentState().name());

        // Reactivate
        var result = engine.resume(Map.of(
            ReactivateRequest.class, new ReactivateRequest("manager")
        ));
        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        assertEquals("Active", engine.currentState().name());
    }

    @Test
    void multiExternalRoutingByType() {
        var engine = Carta.start(userLifecycle());
        engine.resume(Map.of());  // → Active

        // SuspendRequest routes to suspendGuard (not profileUpdateGuard or banGuard)
        engine.resume(Map.of(SuspendRequest.class, new SuspendRequest("Test")));
        assertEquals("Suspended", engine.currentState().name());
    }

    @Test
    void flowInstancePersistence() {
        var engine = Carta.start(userLifecycle());
        engine.resume(Map.of());  // → Active

        // Export to FlowInstance
        FlowInstance instance = engine.toFlowInstance("user-123");
        assertEquals("Active", instance.currentState());
        assertNotNull(instance.context().get(UserId.class));

        // Persist and restore
        var store = Carta.memoryStore();
        store.save(instance);

        FlowInstance loaded = store.load("user-123").orElseThrow();
        var restored = Carta.restore(userLifecycle(), loaded);
        assertEquals("Active", restored.currentState().name());

        // Continue from restored state
        var result = restored.resume(Map.of(
            BanOrder.class, new BanOrder("Fraud", "admin")
        ));
        assertEquals(CartaEngine.ResumeResult.TRANSITIONED, result);
        assertEquals("Banned", restored.currentState().name());
    }

    @Test
    void mermaidShowsMultipleExternals() {
        var mermaid = userLifecycle().toMermaid();
        // Should show multiple arrows from Active
        assertTrue(mermaid.contains("profileUpdateGuard"));
        assertTrue(mermaid.contains("suspendGuard"));
        assertTrue(mermaid.contains("banGuard"));
    }
}
