package com.minuu.httpserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** An HTTP/1.1 response, written to the wire in one call. */
public final class HttpResponse {

    private static final Map<Integer, String> REASONS = Map.ofEntries(
            Map.entry(200, "OK"),
            Map.entry(201, "Created"),
            Map.entry(204, "No Content"),
            Map.entry(400, "Bad Request"),
            Map.entry(403, "Forbidden"),
            Map.entry(404, "Not Found"),
            Map.entry(405, "Method Not Allowed"),
            Map.entry(413, "Content Too Large"),
            Map.entry(500, "Internal Server Error")
    );

    private final int status;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private final byte[] body;

    public HttpResponse(int status, byte[] body) {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
    }

    public HttpResponse header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public int status() {
        return status;
    }

    public byte[] body() {
        return body;
    }

    public Map<String, String> headers() {
        return headers;
    }

    /** Writes status line, headers and body. Content-Length is always set. */
    public void writeTo(OutputStream out, boolean keepAlive) throws IOException {
        String reason = REASONS.getOrDefault(status, "Unknown");
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        for (Map.Entry<String, String> h : headers.entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Connection: ").append(keepAlive ? "keep-alive" : "close").append("\r\n");
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    // ---- factories -------------------------------------------------------

    public static HttpResponse ok(String body, String contentType) {
        return new HttpResponse(200, body.getBytes(StandardCharsets.UTF_8))
                .header("Content-Type", contentType);
    }

    public static HttpResponse okBytes(byte[] body, String contentType) {
        return new HttpResponse(200, body).header("Content-Type", contentType);
    }

    public static HttpResponse text(String body) {
        return ok(body, "text/plain; charset=utf-8");
    }

    public static HttpResponse html(String body) {
        return ok(body, "text/html; charset=utf-8");
    }

    public static HttpResponse json(String body) {
        return ok(body, "application/json; charset=utf-8");
    }

    public static HttpResponse created(String body) {
        return new HttpResponse(201, body.getBytes(StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=utf-8");
    }

    public static HttpResponse noContent() {
        return new HttpResponse(204, new byte[0]);
    }

    public static HttpResponse badRequest(String message) {
        return json(Json.object(Map.of("error", message))).withStatus(400);
    }

    public static HttpResponse notFound(String message) {
        return json(Json.object(Map.of("error", message))).withStatus(404);
    }

    public static HttpResponse forbidden(String message) {
        return json(Json.object(Map.of("error", message))).withStatus(403);
    }

    public static HttpResponse methodNotAllowed(Set<String> allowed) {
        return json(Json.object(Map.of("error", "method not allowed")))
                .withStatus(405)
                .header("Allow", String.join(", ", allowed));
    }

    public static HttpResponse internalError(String message) {
        return json(Json.object(Map.of("error", message))).withStatus(500);
    }

    private HttpResponse withStatus(int newStatus) {
        HttpResponse copy = new HttpResponse(newStatus, body);
        copy.headers.putAll(headers);
        return copy;
    }
}
