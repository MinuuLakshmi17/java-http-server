package com.minuu.httpserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A parsed HTTP/1.1 request.
 *
 * <p>Parsing is deliberately done byte by byte on the raw stream instead of
 * wrapping it in a {@code BufferedReader}: a buffered reader would read
 * ahead past the end of the headers and swallow bytes that belong to the
 * request body. The parser reads one header line at a time, then reads
 * exactly {@code Content-Length} bytes for the body, so pipelined and
 * keep-alive requests on the same connection are handled correctly.</p>
 */
public final class HttpRequest {

    /** Thrown when the request line or headers are malformed. */
    public static final class BadRequestException extends Exception {
        public BadRequestException(String message) {
            super(message);
        }
    }

    private final String method;
    private final String rawTarget;
    private final String path;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final String version;
    private final byte[] body;

    private HttpRequest(String method, String rawTarget, String path,
                        Map<String, String> queryParams,
                        Map<String, String> headers, String version, byte[] body) {
        this.method = method;
        this.rawTarget = rawTarget;
        this.path = path;
        this.queryParams = queryParams;
        this.headers = headers;
        this.version = version;
        this.body = body;
    }

    /**
     * Parses one request from the stream. Returns {@code null} when the peer
     * closed the connection before sending anything (clean EOF).
     */
    public static HttpRequest parse(InputStream in) throws IOException, BadRequestException {
        String requestLine = readLine(in);
        if (requestLine == null) {
            return null; // clean EOF: peer closed the connection
        }
        if (requestLine.isBlank()) {
            throw new BadRequestException("empty request line");
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3) {
            throw new BadRequestException("malformed request line: " + requestLine);
        }
        String method = parts[0];
        String target = parts[1];
        String version = parts[2];
        if (!version.equals("HTTP/1.0") && !version.equals("HTTP/1.1")) {
            throw new BadRequestException("unsupported HTTP version: " + version);
        }
        if (!target.startsWith("/")) {
            throw new BadRequestException("request target must start with '/': " + target);
        }

        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        String line;
        while ((line = readLine(in)) != null) {
            if (line.isEmpty()) {
                break; // end of headers
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new BadRequestException("malformed header line: " + line);
            }
            headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
        }
        if (line == null) {
            throw new BadRequestException("connection closed inside headers");
        }

        String rawPath = target;
        String rawQuery = null;
        int q = target.indexOf('?');
        if (q >= 0) {
            rawPath = target.substring(0, q);
            rawQuery = target.substring(q + 1);
        }
        String path = decode(rawPath);
        Map<String, String> queryParams = parseQuery(rawQuery);

        byte[] body = new byte[0];
        String lengthHeader = headers.get("Content-Length");
        if (lengthHeader != null) {
            int length;
            try {
                length = Integer.parseInt(lengthHeader.trim());
            } catch (NumberFormatException e) {
                throw new BadRequestException("invalid Content-Length: " + lengthHeader);
            }
            if (length < 0) {
                throw new BadRequestException("negative Content-Length: " + length);
            }
            if (length > 10 * 1024 * 1024) {
                throw new BadRequestException("request body too large: " + length + " bytes");
            }
            body = in.readNBytes(length);
            if (body.length != length) {
                throw new BadRequestException("connection closed inside request body");
            }
        }
        return new HttpRequest(method, target, path, queryParams, headers, version, body);
    }

    /** Reads one CRLF- or LF-terminated line. Returns null on EOF before any byte. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                buf.write(b);
            }
        }
        if (b == -1 && buf.size() == 0) {
            return null;
        }
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> params = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                params.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            } else {
                params.put(decode(pair), "");
            }
        }
        return params;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s; // keep raw text on a bad escape instead of failing the request
        }
    }

    public String method() {
        return method;
    }

    public String rawTarget() {
        return rawTarget;
    }

    /** The URL-decoded request path, without the query string. */
    public String path() {
        return path;
    }

    public Map<String, String> queryParams() {
        return queryParams;
    }

    public String queryParam(String name) {
        return queryParams.get(name);
    }

    public Map<String, String> headers() {
        return headers;
    }

    public String header(String name) {
        return headers.get(name);
    }

    public String version() {
        return version;
    }

    public byte[] body() {
        return body;
    }

    public String bodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** True when another request may follow on this connection (HTTP/1.1 default). */
    public boolean keepAlive() {
        String connection = header("Connection");
        if (connection != null) {
            if (connection.equalsIgnoreCase("close")) {
                return false;
            }
            if (connection.equalsIgnoreCase("keep-alive")) {
                return true;
            }
        }
        return version.equals("HTTP/1.1");
    }

    /** Splits the path into segments, ignoring leading, trailing and duplicate slashes. */
    public List<String> pathSegments() {
        List<String> segments = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments;
    }
}
