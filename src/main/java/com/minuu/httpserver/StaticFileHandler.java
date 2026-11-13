package com.minuu.httpserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Serves files from a root directory, with path-traversal protection.
 *
 * <p>The requested path is resolved against the root and normalized; any
 * resolution that escapes the root (for example {@code /static/../secret})
 * is rejected with 403 instead of being served. Directories resolve to
 * their {@code index.html}.</p>
 */
public final class StaticFileHandler {

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("ico", "image/x-icon")
    );

    private final Path root;

    public StaticFileHandler(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public HttpResponse serve(String requestPath) {
        String relative = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;
        if (relative.isEmpty()) {
            relative = "index.html";
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            return HttpResponse.forbidden("path escapes the document root");
        }
        try {
            if (Files.isDirectory(resolved)) {
                resolved = resolved.resolve("index.html");
            }
            if (!Files.isRegularFile(resolved)) {
                return HttpResponse.notFound("file not found: " + requestPath);
            }
            byte[] bytes = Files.readAllBytes(resolved);
            return HttpResponse.okBytes(bytes, contentType(resolved));
        } catch (IOException e) {
            return HttpResponse.internalError("could not read file: " + requestPath);
        }
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return CONTENT_TYPES.getOrDefault(name.substring(dot + 1).toLowerCase(), "application/octet-stream");
    }
}
