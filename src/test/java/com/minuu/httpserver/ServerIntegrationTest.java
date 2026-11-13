package com.minuu.httpserver;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts a real server on an ephemeral port and exercises it over real
 * sockets: HTTP clients for the API, raw sockets for keep-alive,
 * traversal and malformed-input cases.
 */
final class ServerIntegrationTest {

    static void run(TestRunner r) throws Exception {
        r.section("Server integration (live socket)", () -> {
            Path root = Files.createTempDirectory("httpserver-test-root");
            Files.writeString(root.resolve("index.html"),
                    "<html><head><title>java-http-server — Live Demo</title></head><body>demo</body></html>");
            Files.writeString(root.resolve("style.css"), "body { color: red; }");

            KvStore store = new KvStore();
            HttpServer server = new HttpServer(0, 16, Main.buildRouter(store, root));
            Main.StatsHolder.set(server.stats());
            server.start();
            try {
                int port = server.getPort();
                r.check("server bound an ephemeral port", port > 0);
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5)).build();
                String base = "http://127.0.0.1:" + port;

                r.eq("GET / serves dashboard", 200, get(client, base + "/").statusCode());
                r.check("dashboard mentions the project",
                        get(client, base + "/").body().contains("java-http-server"));
                HttpResponse<String> css = get(client, base + "/style.css");
                r.eq("static css served", 200, css.statusCode());
                r.check("css content type",
                        css.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));

                HttpResponse<String> health = get(client, base + "/api/health");
                r.eq("health is 200", 200, health.statusCode());
                r.eq("health body", "{\"status\":\"ok\"}", health.body());

                HttpResponse<String> stats = get(client, base + "/api/stats");
                r.eq("stats is 200", 200, stats.statusCode());
                r.check("stats reports requests", stats.body().contains("requests_served"));

                HttpResponse<String> putNew = put(client, base + "/api/kv/demo", "{\"value\":\"hello\"}");
                r.eq("PUT new key is 201", 201, putNew.statusCode());
                HttpResponse<String> getKey = get(client, base + "/api/kv/demo");
                r.eq("GET key is 200", 200, getKey.statusCode());
                r.check("GET key returns value", getKey.body().contains("hello"));
                r.eq("PUT existing key is 200", 200, put(client, base + "/api/kv/demo", "{\"value\":\"hi\"}").statusCode());
                r.check("key list contains demo", get(client, base + "/api/kv").body().contains("demo"));
                r.eq("DELETE key is 200", 200, delete(client, base + "/api/kv/demo").statusCode());
                r.eq("GET after delete is 404", 404, get(client, base + "/api/kv/demo").statusCode());
                r.eq("GET missing key is 404", 404, get(client, base + "/api/kv/missing").statusCode());
                r.eq("unknown path is 404", 404, get(client, base + "/nope").statusCode());
                r.eq("POST to a GET-only route is 405", 405, post(client, base + "/api/health", "{}").statusCode());
                r.check("405 carries Allow header",
                        post(client, base + "/api/health", "{}").headers().firstValue("Allow").isPresent());
                r.eq("bad JSON body is 400", 400, post(client, base + "/api/kv", "{not json}").statusCode());
                r.eq("missing value is 400", 400, put(client, base + "/api/kv/x", "{}").statusCode());

                // TTL through the API: store with ttl=1, wait, confirm it is gone.
                r.eq("PUT with ttl accepted", 201, put(client, base + "/api/kv/short", "{\"value\":\"v\",\"ttl\":1}").statusCode());
                Thread.sleep(1_200);
                r.eq("ttl key expired over HTTP", 404, get(client, base + "/api/kv/short").statusCode());

                // Raw socket: traversal attempt must not escape the document root.
                String traversal = rawRequest(port, "GET /static/%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                r.check("traversal blocked (403 or 404, never 200)",
                        traversal.contains("HTTP/1.1 403") || traversal.contains("HTTP/1.1 404"));

                // Raw socket: two requests on one keep-alive connection both succeed.
                String pipelined = rawRequest(port,
                        "GET /api/health HTTP/1.1\r\nHost: x\r\n\r\n"
                                + "GET /api/health HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n");
                r.eq("keep-alive serves two requests on one socket", 2, count(pipelined, "HTTP/1.1 200"));

                // Raw socket: garbage is a 400, not a hang or a crash.
                String garbage = rawRequest(port, "GARBAGE\r\n\r\n");
                r.check("malformed request gets 400", garbage.contains("HTTP/1.1 400"));

                // Concurrency: 16 threads x 25 requests, every one 200.
                int threads = 16;
                int perThread = 25;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                CountDownLatch latch = new CountDownLatch(threads);
                AtomicInteger failures = new AtomicInteger();
                for (int t = 0; t < threads; t++) {
                    pool.execute(() -> {
                        try {
                            for (int i = 0; i < perThread; i++) {
                                if (get(client, base + "/api/health").statusCode() != 200) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            failures.addAndGet(perThread);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
                pool.shutdown();
                r.eq("400 concurrent requests all 200", 0, failures.get());

                HttpResponse<String> finalStats = get(client, base + "/api/stats");
                r.check("stats count grew with traffic",
                        requestsServed(finalStats.body()) > 400);
            } finally {
                server.stop();
                Main.StatsHolder.set(null);
            }
        });
    }

    private static long requestsServed(String statsJson) {
        try {
            Object value = Json.parseObject(statsJson).get("requests_served");
            return value instanceof Number n ? n.longValue() : 0;
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> put(HttpClient client, String url, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .PUT(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url))
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .header("Content-Type", "application/json").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> delete(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Sends raw bytes on a socket and reads until the server closes it. */
    private static String rawRequest(int port, String raw) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(StandardCharsets.ISO_8859_1);
        }
    }
}
