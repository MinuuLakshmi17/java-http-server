package com.minuu.httpserver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point. Boots the server with the demo application:
 *
 * <ul>
 *   <li>{@code GET /} — the live demo dashboard (static page)</li>
 *   <li>{@code GET /api/health} — liveness probe</li>
 *   <li>{@code GET /api/stats} — uptime, requests served, active connections, key count</li>
 *   <li>{@code GET /api/kv} — list keys</li>
 *   <li>{@code GET /api/kv/{key}} — read a value</li>
 *   <li>{@code PUT /api/kv/{key}} — store a value ({@code {"value": "...", "ttl": 60}})</li>
 *   <li>{@code POST /api/kv} — create a value ({@code {"key": "...", "value": "..."}})</li>
 *   <li>{@code DELETE /api/kv/{key}} — delete a value</li>
 * </ul>
 *
 * <p>Usage: {@code java -cp out com.minuu.httpserver.Main [--port N] [--threads N] [--root DIR]}</p>
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        int threads = 16;
        Path root = Path.of("public");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--root" -> root = Path.of(args[++i]);
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }

        KvStore store = new KvStore();
        HttpServer server = new HttpServer(port, threads, buildRouter(store, root));
        StatsHolder.set(server.stats());
        server.start();
        System.out.println();
        System.out.println("  java-http-server is live");
        System.out.println("  dashboard : http://localhost:" + server.getPort() + "/");
        System.out.println("  health    : http://localhost:" + server.getPort() + "/api/health");
        System.out.println("  threads   : " + threads + "   document root: " + root.toAbsolutePath());
        System.out.println("  stop with Ctrl+C");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nshutting down...");
            server.stop();
        }));

        // Keep the main thread alive until the JVM is stopped.
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Builds the demo router. Shared with the integration tests so they test the real routes. */
    static Router buildRouter(KvStore store, Path root) {
        Router router = new Router();
        StaticFileHandler staticFiles = new StaticFileHandler(root);

        router.get("/api/health", (req, params) ->
                HttpResponse.json(Json.object(Map.of("status", "ok"))));

        router.get("/api/stats", (req, params) -> {
            // stats are attached by the server at request time; see StatsHolder below
            ServerStats stats = StatsHolder.get();
            return HttpResponse.json(stats == null
                    ? Json.object(Map.of("uptime_seconds", 0L, "requests_served", 0L,
                            "active_connections", 0, "keys", store.size()))
                    : stats.toJson(store.size()));
        });

        router.get("/api/kv", (req, params) -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("keys", store.keys());
            body.put("count", store.size());
            return HttpResponse.json(Json.object(body));
        });

        router.get("/api/kv/{key}", (req, params) -> {
            String value = store.get(params.get("key"));
            if (value == null) {
                return HttpResponse.notFound("key not found: " + params.get("key"));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", params.get("key"));
            body.put("value", value);
            return HttpResponse.json(Json.object(body));
        });

        router.put("/api/kv/{key}", (req, params) -> {
            Map<String, Object> body = parseBody(req);
            if (body == null || !(body.get("value") instanceof String value)) {
                return HttpResponse.badRequest("expected JSON body with a string \"value\"");
            }
            long ttl = body.get("ttl") instanceof Number n ? n.longValue() : 0;
            boolean existed = store.put(params.get("key"), value, ttl);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", params.get("key"));
            out.put("value", value);
            out.put("created", !existed);
            return existed ? HttpResponse.json(Json.object(out))
                    : HttpResponse.created(Json.object(out));
        });

        router.post("/api/kv", (req, params) -> {
            Map<String, Object> body = parseBody(req);
            if (body == null
                    || !(body.get("key") instanceof String key)
                    || !(body.get("value") instanceof String value)) {
                return HttpResponse.badRequest("expected JSON body with string \"key\" and \"value\"");
            }
            long ttl = body.get("ttl") instanceof Number n ? n.longValue() : 0;
            boolean existed = store.put(key, value, ttl);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("key", key);
            out.put("value", value);
            out.put("created", !existed);
            return HttpResponse.json(Json.object(out));
        });

        router.delete("/api/kv/{key}", (req, params) -> {
            boolean deleted = store.delete(params.get("key"));
            if (!deleted) {
                return HttpResponse.notFound("key not found: " + params.get("key"));
            }
            return HttpResponse.json(Json.object(Map.of("deleted", true, "key", params.get("key"))));
        });

        // Anything that is not an API route is a static file under the document root.
        router.fallback((req, params) -> staticFiles.serve(req.path()));
        return router;
    }

    private static Map<String, Object> parseBody(HttpRequest req) {
        if (req.body().length == 0) {
            return null;
        }
        try {
            return Json.parseObject(req.bodyAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The server registers its live stats here so the stats route can read them. */
    static final class StatsHolder {
        private static volatile ServerStats stats;

        static void set(ServerStats s) {
            stats = s;
        }

        static ServerStats get() {
            return stats;
        }
    }
}
