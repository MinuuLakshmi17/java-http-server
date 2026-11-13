package com.minuu.httpserver.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Load generator for java-http-server.
 *
 * <p>Usage: {@code java -cp out com.minuu.httpserver.tools.Benchmark [host] [port] [requests] [threads]}</p>
 *
 * <p>Fires {@code requests} GETs at {@code /api/health} from {@code threads}
 * concurrent workers, after a short warmup, then prints throughput and
 * latency percentiles. Numbers are measured on the machine that runs it;
 * see README.md for the reference run.</p>
 */
public final class Benchmark {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
        int requests = args.length > 2 ? Integer.parseInt(args[2]) : 10_000;
        int threads = args.length > 3 ? Integer.parseInt(args[3]) : 16;

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        URI uri = URI.create("http://" + host + ":" + port + "/api/health");
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();

        // Warmup so JIT compilation does not pollute the measurement.
        for (int i = 0; i < 1_000; i++) {
            client.send(request, HttpResponse.BodyHandlers.discarding());
        }

        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(requests));
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(requests);
        for (int i = 0; i < requests; i++) {
            pool.execute(() -> {
                try {
                    startGate.await();
                    long started = System.nanoTime();
                    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                    latencies.add((System.nanoTime() - started) / 1_000_000);
                    if (response.statusCode() != 200) {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        long started = System.nanoTime();
        startGate.countDown();
        doneGate.await();
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        pool.shutdown();

        Collections.sort(latencies);
        System.out.println();
        System.out.println("  benchmark: " + requests + " GET /api/health from " + threads + " threads");
        System.out.println("  elapsed  : " + elapsedMillis + " ms");
        System.out.println("  throughput: " + String.format("%,d", requests * 1000L / Math.max(1, elapsedMillis)) + " req/s");
        System.out.println("  failures : " + failures.get());
        System.out.println("  latency  : mean " + mean(latencies) + " ms | p50 " + percentile(latencies, 50)
                + " ms | p95 " + percentile(latencies, 95) + " ms | p99 " + percentile(latencies, 99)
                + " ms | max " + latencies.get(latencies.size() - 1) + " ms");
        System.out.println();
    }

    private static long mean(List<Long> values) {
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return values.isEmpty() ? 0 : sum / values.size();
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
