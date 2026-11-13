package com.minuu.httpserver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Runtime counters exposed by the /api/stats endpoint. All fields are thread-safe. */
public final class ServerStats {

    private final long startTimeMillis = System.currentTimeMillis();
    private final AtomicLong requestsServed = new AtomicLong();
    private final AtomicInteger activeConnections = new AtomicInteger();

    public void requestServed() {
        requestsServed.incrementAndGet();
    }

    public void connectionOpened() {
        activeConnections.incrementAndGet();
    }

    public void connectionClosed() {
        activeConnections.decrementAndGet();
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - startTimeMillis) / 1000;
    }

    public long requestsServed() {
        return requestsServed.get();
    }

    public int activeConnections() {
        return activeConnections.get();
    }

    public String toJson(int keyCount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("uptime_seconds", uptimeSeconds());
        values.put("requests_served", requestsServed());
        values.put("active_connections", activeConnections());
        values.put("keys", keyCount);
        return Json.object(values);
    }
}
