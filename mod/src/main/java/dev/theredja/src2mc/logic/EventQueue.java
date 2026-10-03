package dev.theredja.src2mc.logic;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Source's {@code CEventQueue}: inputs waiting for their time. Events fire in time order, and those
 * due at the same time in the order they were added. A target is held by name and looked up only
 * when the event fires, as Source does; an event aimed at one entity directly holds it instead.
 */
final class EventQueue {
    record Event(double time, long order, String target, LogicEntity direct, String input, String value,
                 Actor activator, LogicEntity caller) {}

    private final PriorityQueue<Event> events = new PriorityQueue<>((a, b) -> {
        int byTime = Double.compare(a.time, b.time);
        return byTime != 0 ? byTime : Long.compare(a.order, b.order);
    });
    private long order;

    void add(double time, String target, LogicEntity direct, String input, String value, Actor activator, LogicEntity caller) {
        events.add(new Event(time, order++, target, direct, input, value, activator, caller));
    }

    /** The next event due at or before {@code now}, removed; null when none is. */
    Event poll(double now) {
        Event next = events.peek();
        if (next == null || next.time > now) return null;
        return events.poll();
    }

    /** {@code CancelEvents}: drops every event the caller added. */
    int cancel(LogicEntity caller) {
        List<Event> kept = new ArrayList<>(events.size());
        int removed = 0;
        for (Event event : events) {
            if (event.caller == caller) removed++;
            else kept.add(event);
        }
        if (removed > 0) { events.clear(); events.addAll(kept); }
        return removed;
    }

    /** Every waiting event, in firing order. */
    List<Event> pending() {
        List<Event> list = new ArrayList<>(events);
        list.sort(events.comparator());
        return list;
    }

    int size() { return events.size(); }

    void clear() { events.clear(); }
}
