package com.minuu.httpserver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class RouterTest {

    static void run(TestRunner r) {
        r.section("Router", () -> {
            Router router = new Router();
            router.get("/api/health", (req, p) -> HttpResponse.text("ok"));
            router.get("/api/kv/{key}", (req, p) -> HttpResponse.text("value:" + p.get("key")));
            router.put("/api/kv/{key}", (req, p) -> HttpResponse.text("stored:" + p.get("key")));
            router.get("/api/kv", (req, p) -> HttpResponse.text("list"));

            r.eq("literal route", "ok", body(router, "GET", "/api/health"));
            r.eq("param captured", "value:hello", body(router, "GET", "/api/kv/hello"));
            r.eq("list route", "list", body(router, "GET", "/api/kv"));
            r.eq("PUT param route", "stored:hello", body(router, "PUT", "/api/kv/hello"));
            r.eq("unknown path is 404", 404, status(router, "GET", "/nope"));
            r.eq("wrong method is 405", 405, status(router, "DELETE", "/api/health"));
            r.eq("405 carries Allow header", true,
                    router.handle(req("DELETE", "/api/health")).headers().containsKey("Allow"));
            r.eq("trailing slash tolerated", "ok", body(router, "GET", "/api/health/"));
            r.eq("extra segment is 404", 404, status(router, "GET", "/api/kv/a/b"));

            Router withFallback = new Router();
            withFallback.fallback((req, p) -> HttpResponse.text("fallback:" + req.path()));
            r.eq("fallback serves unmatched", "fallback:/anything", body(withFallback, "GET", "/anything"));
        });
    }

    private static HttpRequest req(String method, String path) throws Exception {
        String raw = method + " " + path + " HTTP/1.1\r\n\r\n";
        return HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static String body(Router router, String method, String path) throws Exception {
        return new String(router.handle(req(method, path)).body(), StandardCharsets.UTF_8);
    }

    private static int status(Router router, String method, String path) throws Exception {
        return router.handle(req(method, path)).status();
    }
}
