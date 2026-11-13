package com.minuu.httpserver;

import java.util.Map;

/** Handles one request. {@code pathParams} holds values captured from {@code {name}} segments. */
@FunctionalInterface
public interface Handler {
    HttpResponse handle(HttpRequest request, Map<String, String> pathParams);
}
