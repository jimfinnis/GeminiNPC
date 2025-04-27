package org.pale.gemininpc;

import java.util.LinkedList;

public class EventRateTracker {

    private final LinkedList<Long> eventTimestamps = new LinkedList<>();
    private static final long ONE_MINUTE_MILLIS = 60_000L;

    /**
     * Record an event occurring at the current time.
     */
    public synchronized void event() {
        long now = System.currentTimeMillis();
        eventTimestamps.addLast(now);
        cleanupOldEvents(now);
    }

    /**
     * Get the number of events in the last minute
     */
    public synchronized double getEventsInLastMinute() {
        long now = System.currentTimeMillis();
        cleanupOldEvents(now);
        return eventTimestamps.size();
    }

    /**
     * Remove events older than one minute from the list.
     */
    private void cleanupOldEvents(long now) {
        while (!eventTimestamps.isEmpty() && (now - eventTimestamps.getFirst() > ONE_MINUTE_MILLIS)) {
            eventTimestamps.removeFirst();
        }
    }
}
