package org.unlaxer;

import java.util.*;

/**
 * Bipartite graph of data types and processors/guards.
 *
 * Built from a {@link StateMachine} definition, provides queries:
 * <ul>
 *   <li>{@link #availableAt(String)} — types reachable at a state</li>
 *   <li>{@link #producersOf(Class)} — states where a type is produced</li>
 *   <li>{@link #consumersOf(Class)} — states where a type is consumed</li>
 *   <li>{@link #deadData()} — types produced but never consumed</li>
 *   <li>{@link #toMarkdown()} — human-readable data-flow report</li>
 * </ul>
 */
public final class DataFlowGraph {

    private final StateMachine machine;
    private final Map<Class<?>, List<String>> producers = new LinkedHashMap<>();
    private final Map<Class<?>, List<String>> consumers = new LinkedHashMap<>();
    private final Map<String, Set<Class<?>>> availability = new LinkedHashMap<>();

    DataFlowGraph(StateMachine machine) {
        this.machine = machine;
        collectProducersConsumers();
        computeAvailability();
    }

    private void collectProducersConsumers() {
        for (Transition t : machine.transitions()) {
            if (t.processor() != null) {
                for (Class<?> c : t.processor().requires()) {
                    consumers.computeIfAbsent(c, k -> new ArrayList<>()).add(t.from());
                }
                for (Class<?> c : t.processor().produces()) {
                    producers.computeIfAbsent(c, k -> new ArrayList<>()).add(
                        t.to() != null ? t.to() : t.from());
                }
            }
            if (t.transitionGuard() != null) {
                for (Class<?> c : t.transitionGuard().requires()) {
                    // External data is both produced (by caller) and consumed (by guard)
                    producers.computeIfAbsent(c, k -> new ArrayList<>()).add(t.from() + "[ext]");
                    consumers.computeIfAbsent(c, k -> new ArrayList<>()).add(t.from());
                }
            }
            if (t.branchProcessor() != null) {
                for (Class<?> c : t.branchProcessor().requires()) {
                    consumers.computeIfAbsent(c, k -> new ArrayList<>()).add(t.from());
                }
            }
        }
    }

    private void computeAvailability() {
        // Forward walk: BFS from initial state, accumulating produced types
        StateNode root = machine.root();
        StateNode initial = machine.resolveToLeaf(root);

        Set<Class<?>> initialTypes = new LinkedHashSet<>();
        availability.put(initial.name(), initialTypes);

        Queue<String> queue = new ArrayDeque<>();
        queue.add(initial.name());
        Set<String> visited = new HashSet<>();

        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) continue;

            Set<Class<?>> available = availability.getOrDefault(current, new LinkedHashSet<>());

            for (Transition t : machine.transitions()) {
                if (!t.from().equals(current)) continue;

                Set<Class<?>> newAvailable = new LinkedHashSet<>(available);
                if (t.processor() != null) newAvailable.addAll(t.processor().produces());
                if (t.transitionGuard() != null) newAvailable.addAll(t.transitionGuard().requires());

                List<String> targets = new ArrayList<>();
                if (t.to() != null) targets.add(t.to());
                if (t.branchTargets() != null) targets.addAll(t.branchTargets().values());

                for (String target : targets) {
                    Set<Class<?>> existing = availability.get(target);
                    if (existing == null) {
                        availability.put(target, new LinkedHashSet<>(newAvailable));
                    } else {
                        // Merge (union for optimistic analysis)
                        existing.addAll(newAvailable);
                    }
                    queue.add(target);
                }
            }
        }
    }

    /** Types available in context when at this state. */
    public Set<Class<?>> availableAt(String state) {
        return Collections.unmodifiableSet(availability.getOrDefault(state, Set.of()));
    }

    /** States/transitions that produce this type. */
    public List<String> producersOf(Class<?> type) {
        return Collections.unmodifiableList(producers.getOrDefault(type, List.of()));
    }

    /** States/transitions that consume (require) this type. */
    public List<String> consumersOf(Class<?> type) {
        return Collections.unmodifiableList(consumers.getOrDefault(type, List.of()));
    }

    /** Types produced but never consumed by any processor. */
    public List<Class<?>> deadData() {
        var dead = new ArrayList<Class<?>>();
        for (Class<?> type : producers.keySet()) {
            if (!consumers.containsKey(type)) dead.add(type);
        }
        return dead;
    }

    /** All types involved in the data flow. */
    public Set<Class<?>> allTypes() {
        var all = new LinkedHashSet<>(producers.keySet());
        all.addAll(consumers.keySet());
        return Collections.unmodifiableSet(all);
    }

    /** Generate a Markdown report of the data flow. */
    public String toMarkdown() {
        var sb = new StringBuilder("# Data Flow Report: ")
            .append(machine.name()).append("\n\n");

        sb.append("## Producers\n\n");
        if (producers.isEmpty()) {
            sb.append("_No producers (no processors with produces())_\n\n");
        } else {
            for (var entry : producers.entrySet()) {
                sb.append("- `").append(entry.getKey().getSimpleName()).append("` <- ")
                  .append(String.join(", ", entry.getValue())).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Consumers\n\n");
        if (consumers.isEmpty()) {
            sb.append("_No consumers (no processors with requires())_\n\n");
        } else {
            for (var entry : consumers.entrySet()) {
                sb.append("- `").append(entry.getKey().getSimpleName()).append("` -> ")
                  .append(String.join(", ", entry.getValue())).append("\n");
            }
            sb.append("\n");
        }

        var dead = deadData();
        if (!dead.isEmpty()) {
            sb.append("## Dead Data\n\n");
            sb.append("_Produced but never consumed:_\n\n");
            for (Class<?> d : dead) {
                sb.append("- `").append(d.getSimpleName()).append("`\n");
            }
            sb.append("\n");
        }

        sb.append("## Availability\n\n");
        for (var entry : availability.entrySet()) {
            sb.append("- **").append(entry.getKey()).append("**: ");
            if (entry.getValue().isEmpty()) {
                sb.append("_(empty)_");
            } else {
                var names = entry.getValue().stream()
                    .map(Class::getSimpleName).toList();
                sb.append(String.join(", ", names));
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
