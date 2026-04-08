# Carta

**Hierarchical state machine library inspired by David Harel's Statechart formalism (1987).**

Born from a [DGE session](https://github.com/opaopa6969/tramli) comparing Statechart theory with data-flow verified state machines.

## Design Philosophy

Carta prioritizes **structural expressiveness** over data-flow verification:

- **H1**: States are hierarchical (nested)
- **H2**: Events are first-class citizens (not implicit)
- **H3**: Entry/exit actions are declarative
- **H4**: Guards and actions are separated (guards are pure conditions, actions produce side effects)
- **H5**: The diagram IS the specification

## Comparison with tramli

| Aspect | Carta | tramli |
|--------|-------|--------|
| State structure | Hierarchical (nested) | Flat enum |
| Events | Explicit `Event` type | Implicit (requires-based routing) |
| Entry/exit | Yes | No |
| Guard role | Pure condition | Condition + data injection |
| Data-flow verification | No | Yes (requires/produces) |
| Expressiveness | ★★★ | ★☆☆ |
| Verification precision | ★☆☆ | ★★★ |

> "Structural expressiveness and verification precision are fundamentally in tension.
> Carta maximizes expressiveness. tramli maximizes verification."
> — DGE Session, 2026-04-08

## Quick Start

```java
Event paymentReceived = Event.of("PaymentReceived");
Event shipped = Event.of("Shipped");

var order = Carta.define("Order")
    .initial("Created")
    .state("Processing")
        .onEntry(ctx -> ctx.put("processing_started", Instant.now()))
        .initial("PaymentPending")
        .state("Confirmed")
        .terminal("Shipped")
    .terminal("Cancelled")
    .transition().from("Created").on(paymentReceived).to("PaymentPending")
    .transition().from("PaymentPending").on(paymentReceived)
        .guard(ctx -> ctx.get("payment_valid", Boolean.class))
        .action(ctx -> ctx.put("confirmed_at", Instant.now()))
        .to("Confirmed")
    .transition().from("Confirmed").on(shipped).to("Shipped")
    .build();
```

## License

MIT

## Origin

Carta was designed by David Harel (as a character) in a DGE dialogue session
facilitated by [@opaopa6969](https://github.com/opaopa6969), exploring what a
Statechart expert would build if tasked with creating a modern state machine library.
