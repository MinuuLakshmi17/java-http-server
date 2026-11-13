package com.minuu.httpserver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class KvStoreTest {

    static void run(TestRunner r) {
        r.section("KvStore", () -> {
            KvStore store = new KvStore();
            r.eq("empty size", 0, store.size());
            r.check("put returns false for new key", !store.put("a", "1", 0));
            r.eq("get returns value", "1", store.get("a"));
            r.check("put returns true for existing key", store.put("a", "2", 0));
            r.eq("overwrite replaces value", "2", store.get("a"));
            r.eq("missing key is null", null, store.get("nope"));
            r.check("delete existing", store.delete("a"));
            r.check("delete missing is false", !store.delete("a"));

            store.put("b", "x", 0);
            store.put("a", "x", 0);
            store.put("c", "x", 0);
            r.eq("keys sorted", List.of("a", "b", "c"), store.keys());
            r.eq("size counts live keys", 3, store.size());

            store.put("temp", "gone soon", 1);
            r.eq("ttl key readable before expiry", "gone soon", store.get("temp"));
            Thread.sleep(1_200);
            r.eq("ttl key gone after expiry", null, store.get("temp"));
            r.eq("expired key not listed", List.of("a", "b", "c"), store.keys());

            // Concurrent writers: 8 threads x 100 distinct keys must all land.
            KvStore concurrent = new KvStore();
            int threads = 8;
            int perThread = 100;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            for (int t = 0; t < threads; t++) {
                int threadId = t;
                pool.execute(() -> {
                    for (int i = 0; i < perThread; i++) {
                        concurrent.put("t" + threadId + "-" + i, "v", 0);
                    }
                    latch.countDown();
                });
            }
            latch.await();
            pool.shutdown();
            r.eq("concurrent puts all land", threads * perThread, concurrent.size());
            List<String> all = new ArrayList<>(concurrent.keys());
            r.eq("concurrent keys all readable", threads * perThread, all.size());
        });
    }
}
