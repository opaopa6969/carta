package org.unlaxer;

import org.junit.jupiter.api.Test;
import java.util.*;
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
        // Still inside Processing (LCA-based: exit only fires when crossing boundary)
        assertEquals(true, engine.context().get("processing", Boolean.class));
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

        // Exit fires when crossing Processing boundary (e.g., CANCEL)
        engine.send(CANCEL);
        assertEquals("Cancelled", engine.currentState().name());
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

    // ─── New tests: CartaException, StateContext, StateNode, StateMachine ───

    @Test
    void cartaExceptionHasCodeAndMessage() {
        var ex = new CartaException("MY_CODE", "something went wrong");
        assertEquals("MY_CODE", ex.code());
        assertTrue(ex.getMessage().contains("MY_CODE"));
        assertTrue(ex.getMessage().contains("something went wrong"));
    }

    @Test
    void cartaExceptionDefaultCode() {
        var ex = new CartaException("plain message");
        assertEquals("CARTA_ERROR", ex.code());
        assertEquals("plain message", ex.getMessage());
    }

    @Test
    void stateContextStringAndTypeKeyedCoexist() {
        var ctx = new StateContext();
        ctx.put("label", "hello");
        ctx.put(Integer.class, 42);

        assertEquals("hello", ctx.get("label", String.class));
        assertEquals(42, ctx.get(Integer.class));
        assertTrue(ctx.has("label"));
        assertTrue(ctx.has(Integer.class));
        assertFalse(ctx.has("missing"));
        assertFalse(ctx.has(Long.class));
    }

    @Test
    void stateContextFindReturnsEmpty() {
        var ctx = new StateContext();
        assertTrue(ctx.find("nope", String.class).isEmpty());
        assertTrue(ctx.find(Double.class).isEmpty());
    }

    @Test
    void stateContextGetMissingKeyThrows() {
        var ctx = new StateContext();
        assertThrows(CartaException.class, () -> ctx.get("absent", String.class));
        assertThrows(CartaException.class, () -> ctx.get(Long.class));
    }

    @Test
    void stateContextSnapshot() {
        var ctx = new StateContext();
        ctx.put("k", "v");
        ctx.put(Integer.class, 7);
        var snap = ctx.snapshot();
        assertEquals("v", snap.get("k"));
        assertTrue(snap.containsKey("@Integer"));
        assertEquals(7, snap.get("@Integer"));
    }

    @Test
    void stateMachineFindStatePresent() {
        var machine = orderMachine();
        assertTrue(machine.findState("Created").isPresent());
        assertTrue(machine.findState("Shipped").isPresent());
    }

    @Test
    void stateMachineFindStateMissing() {
        var machine = orderMachine();
        assertTrue(machine.findState("NoSuchState").isEmpty());
    }

    @Test
    void stateMachineStateThrowsForUnknown() {
        var machine = orderMachine();
        var ex = assertThrows(CartaException.class, () -> machine.state("Ghost"));
        assertTrue(ex.getMessage().contains("Ghost"));
        assertEquals("UNKNOWN_STATE", ex.code());
    }

    @Test
    void stateNodeProperties() {
        var machine = orderMachine();
        var created = machine.state("Created");
        assertFalse(created.isTerminal());
        assertTrue(created.isInitial());
        assertTrue(created.isLeaf());
        assertFalse(created.isComposite());

        var processing = machine.state("Processing");
        assertTrue(processing.isComposite());
        assertFalse(processing.isLeaf());
        assertFalse(processing.isTerminal());
    }

    @Test
    void stateNodePath() {
        var machine = orderMachine();
        var paymentPending = machine.state("PaymentPending");
        var path = paymentPending.path();
        // Path: Order → Processing → PaymentPending
        assertEquals(List.of("Order", "Processing", "PaymentPending"), path);
    }

    @Test
    void stateNodeFindDescendant() {
        var machine = orderMachine();
        var order = machine.root();
        var found = order.findDescendant("PaymentPending");
        assertTrue(found.isPresent());
        assertEquals("PaymentPending", found.get().name());
        assertTrue(order.findDescendant("NoWhere").isEmpty());
    }

    @Test
    void sendOnCompletedMachineReturnsFalse() {
        var engine = Carta.start(orderMachine());
        engine.send(START);
        engine.send(PAYMENT, "amount", 100);
        engine.send(SHIP);
        assertTrue(engine.isCompleted());
        assertFalse(engine.send(START));
    }

    @Test
    void sendUnknownEventReturnsFalse() {
        var engine = Carta.start(orderMachine());
        Event unknown = Event.of("Unknown");
        assertFalse(engine.send(unknown));
        assertEquals("Created", engine.currentState().name());
    }

    @Test
    void mermaidDataFlowDiagram() {
        // toDataFlowMermaid doesn't throw and contains flowchart header
        var mermaid = orderMachine().toDataFlowMermaid();
        assertTrue(mermaid.contains("flowchart LR"));
    }

    @Test
    void validationRejectsUnknownTransitionEndpoints() {
        var ex = assertThrows(CartaException.class, () ->
            Carta.define("BadEndpoints")
                .root("BadEndpoints")
                    .initial("A")
                    .terminal("B")
                .transition().from("A").on(Event.of("go")).to("NONEXISTENT")
                .build()
        );
        assertEquals("INVALID_DEFINITION", ex.code());
        assertTrue(ex.getMessage().contains("unknown state"));
    }

    @Test
    void validationRejectsTerminalWithOutgoingTransition() {
        var ex = assertThrows(CartaException.class, () ->
            Carta.define("TerminalOutgoing")
                .root("TerminalOutgoing")
                    .initial("A")
                    .terminal("B")
                    .terminal("C")
                .transition().from("B").on(Event.of("go")).to("C")
                .build()
        );
        assertEquals("INVALID_DEFINITION", ex.code());
        assertTrue(ex.getMessage().contains("outgoing transition"));
    }

    @Test
    void guardOutputVariants() {
        GuardOutput accepted = GuardOutput.accepted();
        assertTrue(accepted instanceof GuardOutput.Accepted a && a.data().isEmpty());

        GuardOutput rejected = GuardOutput.rejected("bad input");
        assertTrue(rejected instanceof GuardOutput.Rejected r && r.reason().equals("bad input"));

        GuardOutput expired = GuardOutput.expired();
        assertTrue(expired instanceof GuardOutput.Expired);

        Map<Class<?>, Object> data = Map.of(String.class, "hello");
        GuardOutput acceptedWithData = GuardOutput.accepted(data);
        assertTrue(acceptedWithData instanceof GuardOutput.Accepted aw && aw.data().containsKey(String.class));
    }

    @Test
    void eventEqualityAndHashCode() {
        Event e1 = Event.of("Click");
        Event e2 = Event.of("Click");
        Event e3 = Event.of("Hover");
        assertEquals(e1, e2);
        assertNotEquals(e1, e3);
        assertEquals(e1.hashCode(), e2.hashCode());
        assertEquals("Click", e1.name());
        assertTrue(e1.toString().contains("Click"));
    }

    @Test
    void inMemoryFlowStoreOperations() {
        var store = Carta.memoryStore();
        assertEquals(0, store.size());

        var instance = new FlowInstance("flow-1", "SomeState");
        store.save(instance);
        assertEquals(1, store.size());

        var loaded = store.load("flow-1");
        assertTrue(loaded.isPresent());
        assertEquals("SomeState", loaded.get().currentState());

        assertTrue(store.load("missing").isEmpty());

        store.delete("flow-1");
        assertEquals(0, store.size());
        assertTrue(store.load("flow-1").isEmpty());
    }

    @Test
    void inMemoryFlowStoreAllReturnsAllInstances() {
        var store = Carta.memoryStore();
        store.save(new FlowInstance("a", "X"));
        store.save(new FlowInstance("b", "Y"));
        assertEquals(2, store.all().size());
    }

    @Test
    void flowInstanceToString() {
        var fi = new FlowInstance("id-99", "MyState");
        assertTrue(fi.toString().contains("id-99"));
        assertTrue(fi.toString().contains("MyState"));
    }

    @Test
    void toFlowInstancePreservesStateAndId() {
        var engine = Carta.start(orderMachine());
        engine.send(START);
        var fi = engine.toFlowInstance("order-abc");
        assertEquals("order-abc", fi.id());
        assertEquals("PaymentPending", fi.currentState());
        assertNotNull(fi.createdAt());
        assertNotNull(fi.updatedAt());
    }

    // ─── Boundary / regression tests ───────────────────────────

    /**
     * Event.of(null) must throw NullPointerException (Objects.requireNonNull)
     * rather than silently creating an event with a null name. This pins the
     * null-rejection contract on the public Event factory.
     */
    @Test
    void eventOfNullThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> Event.of(null));
    }

    /**
     * When a child state has a transition for the same event whose guard
     * fails, the engine must bubble up to the parent's transition for that
     * same event. findEventTransition iterates current→parent and evaluates
     * each candidate's guard, continuing on failure — this pins that
     * continuation behaviour.
     */
    @Test
    void failedChildGuardBubblesToParentTransitionForSameEvent() {
        // Root initial child is Start; entering Group via event lands on A
        // (Group's initial child). A has a "toggle" event with a guard that
        // always fails. Group (parent) also has a "toggle" event (bubbling)
        // that should fire when the child guard rejects.
        Event enter = Event.of("enter");
        Event toggle = Event.of("toggle");
        var machine = Carta.define("BubbleOnGuardFail")
            .root("BubbleOnGuardFail")
                .initial("Start")
                .state("Group")
                    .initial("A")
                    .state("B").end()
                .end()
                .terminal("Exited")
            .transition().from("Start").on(enter).to("A")
            // child A: toggle with a guard that always fails
            .transition().from("A").on(toggle)
                .guard(ctx -> false)
                .to("B")
            // parent Group: toggle that exits (should fire via bubbling)
            .transition().from("Group").on(toggle).to("Exited")
            .build();

        var engine = Carta.start(machine);
        assertEquals("Start", engine.currentState().name());
        engine.send(enter);
        assertEquals("A", engine.currentState().name());
        assertTrue(engine.send(toggle),
            "child guard failure must bubble to parent transition for the same event");
        assertEquals("Exited", engine.currentState().name());
        assertTrue(engine.isCompleted());
    }
}
