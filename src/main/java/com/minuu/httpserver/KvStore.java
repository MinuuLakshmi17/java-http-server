package com.minuu.httpserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe in-memory key-value store with optional per-key TTL.
 *
 * <p>Expiry is lazy: an expired entry is removed when it is next read or
 * listed, so no background thread is needed. All operations are safe to
 * call from any request-handling thread concurrently.</p>
 */
public final class KvStore {

    private static final class Entry {
        final String value;
        /** Epoch milliseconds at which this entry expires, or 0 for no expiry. */
        final long expiresAt;

        Entry(String value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }

        boolean isExpired(long now) {
            return expiresAt > 0 && now >= expiresAt;
        }
    }

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    /** Stores a value. {@code ttlSeconds} of 0 or less means no expiry. Returns true if the key already existed. */
    public boolean put(String key, String value, long ttlSeconds) {
        long expiresAt = ttlSeconds > 0 ? System.currentTimeMillis() + ttlSeconds * 1000L : 0;
        return map.put(key, new Entry(value, expiresAt)) != null;
    }

    /** Returns the value, or null when the key is absent or expired. */
    public String get(String key) {
        Entry entry = map.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            map.remove(key, entry);
            return null;
        }
        return entry.value;
    }

    /** Deletes a key. Returns true when a live entry was removed. */
    public boolean delete(String key) {
        Entry entry = map.get(key);
        if (entry == null) {
            return false;
        }
        if (entry.isExpired(System.currentTimeMillis())) {
            map.remove(key, entry);
            return false;
        }
        return map.remove(key, entry);
    }

    /** Sorted list of live keys. */
    public List<String> keys() {
        long now = System.currentTimeMillis();
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            if (e.getValue().isExpired(now)) {
                map.remove(e.getKey(), e.getValue());
            } else {
                result.add(e.getKey());
            }
        }
        Collections.sort(result);
        return result;
    }

    /** Number of live keys. */
    public int size() {
        return keys().size();
    }
}
