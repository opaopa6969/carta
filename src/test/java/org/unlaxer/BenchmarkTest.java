package org.unlaxer;

import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Reproducible micro-benchmark for CartaEngine.
 *
 * Zero-dependency (no JMH): uses System.nanoTime() with warmup and
 * multiple iterations, following JMH conventions conceptually.
 *
 * Run: mvn -Dtest=BenchmarkTest test
 *
 * Outputs machine-readable lines starting with "BENCH:" for parsing.
 */
class BenchmarkTest {

    static final Event START = Event.of("Start");
    static final Event PAYMENT = Event.of("PaymentReceived");
    static final Event SHIP = Event.of("Ship");
    static final Event CANCEL = Event.of("Cancel");

    /** Order flow used as the benchmark workload. */
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

    /** Single happy-path run: Created -> PaymentPending -> Confirmed -> Shipped. */
    private void runOnce(CartaEngine engine) {
        engine.send(START);
        engine.send(PAYMENT, "amount", 1000);
        engine.send(SHIP);
    }

    @Test
    void benchmarkHappyPath() {
        StateMachine machine = orderMachine();

        // Warmup: 100_000 iterations to trigger JIT compilation
        int warmup = 100_000;
        for (int i = 0; i < warmup; i++) {
            runOnce(Carta.start(machine));
        }

        // Measure: 7 forks x 100_000 iterations each
        int forks = 7;
        int itersPerFork = 100_000;
        long[] forkNanos = new long[forks];
        long[] forkCounts = new long[forks];

        for (int f = 0; f < forks; f++) {
            long total = 0;
            long count = 0;
            // small batch to reduce nanoTime overhead amortization
            for (int i = 0; i < itersPerFork; i++) {
                long t0 = System.nanoTime();
                runOnce(Carta.start(machine));
                long t1 = System.nanoTime();
                total += (t1 - t0);
                count++;
            }
            forkNanos[f] = total;
            forkCounts[f] = count;
        }

        // Report per-fork ns/operation (3 sends per op)
        System.out.println("BENCH: --- benchmarkHappyPath ---");
        for (int f = 0; f < forks; f++) {
            double nsPerOp = (double) forkNanos[f] / forkCounts[f];
            System.out.printf("BENCH: fork=%d ns_per_3send_op=%.1f ns_per_send=%.1f%n",
                f, nsPerOp, nsPerOp / 3.0);
        }
        // Summary: median fork
        double[] perOp = new double[forks];
        for (int f = 0; f < forks; f++) perOp[f] = (double) forkNanos[f] / forkCounts[f];
        double[] sorted = perOp.clone();
        Arrays.sort(sorted);
        double median = sorted[forks / 2];
        System.out.printf("BENCH: median ns_per_3send_op=%.1f ns_per_send=%.1f%n",
            median, median / 3.0);
        System.out.printf("BENCH: machine=%s jvm=%s warmup=%d forks=%d iters=%d%n",
            System.getProperty("java.vm.name"),
            System.getProperty("java.version"),
            warmup, forks, itersPerFork);
    }

    /** Benchmark Event-mode send() with single transition (no auto-chain). */
    @Test
    void benchmarkSingleEventSend() {
        StateMachine machine = orderMachine();

        int warmup = 200_000;
        for (int i = 0; i < warmup; i++) {
            CartaEngine e = Carta.start(machine);
            e.send(START);
        }

        int forks = 7;
        int itersPerFork = 200_000;
        long[] forkNanos = new long[forks];

        for (int f = 0; f < forks; f++) {
            long total = 0;
            for (int i = 0; i < itersPerFork; i++) {
                CartaEngine e = Carta.start(machine);
                long t0 = System.nanoTime();
                e.send(START);
                long t1 = System.nanoTime();
                total += (t1 - t0);
            }
            forkNanos[f] = total;
        }

        System.out.println("BENCH: --- benchmarkSingleEventSend ---");
        double[] perOp = new double[forks];
        for (int f = 0; f < forks; f++) {
            perOp[f] = (double) forkNanos[f] / itersPerFork;
            System.out.printf("BENCH: fork=%d ns_per_send=%.1f%n", f, perOp[f]);
        }
        double[] sorted = perOp.clone();
        Arrays.sort(sorted);
        double median = sorted[forks / 2];
        System.out.printf("BENCH: median ns_per_send=%.1f%n", median);
    }

    /** Benchmark engine construction cost. */
    @Test
    void benchmarkEngineConstruction() {
        StateMachine machine = orderMachine();

        int warmup = 200_000;
        for (int i = 0; i < warmup; i++) {
            Carta.start(machine);
        }

        int forks = 7;
        int itersPerFork = 200_000;
        long[] forkNanos = new long[forks];

        for (int f = 0; f < forks; f++) {
            long total = 0;
            for (int i = 0; i < itersPerFork; i++) {
                long t0 = System.nanoTime();
                CartaEngine e = Carta.start(machine);
                long t1 = System.nanoTime();
                total += (t1 - t0);
                if (e == null) throw new AssertionError();
            }
            forkNanos[f] = total;
        }

        System.out.println("BENCH: --- benchmarkEngineConstruction ---");
        double[] perOp = new double[forks];
        for (int f = 0; f < forks; f++) {
            perOp[f] = (double) forkNanos[f] / itersPerFork;
            System.out.printf("BENCH: fork=%d ns_per_construct=%.1f%n", f, perOp[f]);
        }
        double[] sorted = perOp.clone();
        Arrays.sort(sorted);
        double median = sorted[forks / 2];
        System.out.printf("BENCH: median ns_per_construct=%.1f%n", median);
    }
}
