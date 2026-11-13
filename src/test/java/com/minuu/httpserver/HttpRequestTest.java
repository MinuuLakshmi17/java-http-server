package com.minuu.httpserver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

final class HttpRequestTest {

    static void run(TestRunner r) {
        r.section("HttpRequest parsing", () -> {
            HttpRequest get = parse("GET /api/kv/my%20key?lang=en&x=1 HTTP/1.1\r\nHost: localhost\r\nAccept: application/json\r\n\r\n");
            r.eq("method", "GET", get.method());
            r.eq("path decoded", "/api/kv/my key", get.path());
            r.eq("query param lang", "en", get.queryParam("lang"));
            r.eq("query param x", "1", get.queryParam("x"));
            r.eq("header case-insensitive", "application/json", get.header("accept"));
            r.eq("HTTP/1.1 keeps alive", true, get.keepAlive());
            r.eq("segments", java.util.List.of("api", "kv", "my key"), get.pathSegments());

            HttpRequest post = parse("POST /api/kv HTTP/1.0\r\nContent-Length: 11\r\n\r\nhello world");
            r.eq("POST method", "POST", post.method());
            r.eq("POST body", "hello world", post.bodyAsString());
            r.eq("HTTP/1.0 closes by default", false, post.keepAlive());

            HttpRequest close = parse("GET / HTTP/1.1\r\nConnection: close\r\n\r\n");
            r.eq("Connection: close wins", false, close.keepAlive());

            HttpRequest keepAlive10 = parse("GET / HTTP/1.0\r\nConnection: keep-alive\r\n\r\n");
            r.eq("HTTP/1.0 keep-alive header honored", true, keepAlive10.keepAlive());

            expectBad(r, "GARBAGE\r\n\r\n", "garbage request line rejected");
            expectBad(r, "GET /no-version\r\n\r\n", "request line without version rejected");
            expectBad(r, "GET relative/path HTTP/1.1\r\n\r\n", "target not starting with / rejected");
            expectBad(r, "GET / HTTP/2.0\r\n\r\n", "unsupported version rejected");
            expectBad(r, "GET / HTTP/1.1\r\nBadHeaderLine\r\n\r\n", "header without colon rejected");
            expectBad(r, "POST / HTTP/1.1\r\nContent-Length: abc\r\n\r\n", "bad Content-Length rejected");
            expectBad(r, "POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort", "truncated body rejected");
        });
    }

    private static HttpRequest parse(String raw) throws Exception {
        HttpRequest req = HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
        if (req == null) {
            throw new AssertionError("expected a request, got EOF");
        }
        return req;
    }

    private static void expectBad(TestRunner r, String raw, String name) {
        try {
            HttpRequest.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
            r.check(name, false);
        } catch (HttpRequest.BadRequestException e) {
            r.check(name, true);
        } catch (Exception e) {
            r.check(name + " (wrong exception: " + e + ")", false);
        }
    }
}
