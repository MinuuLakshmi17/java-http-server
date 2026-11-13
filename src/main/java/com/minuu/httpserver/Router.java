package com.minuu.httpserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A tiny method-and-path router.
 *
 * <p>Patterns are slash-separated segments; a segment written as
 * {@code {name}} captures one path segment into the handler's
 * {@code pathParams} map. Routing is exact: a path that matches a pattern
 * under a different method produces 405 (with an {@code Allow} header)
 * instead of 404, and a path that matches nothing produces 404.</p>
 */
public final class Router {

    private static final class Route {
        final String method;
        final List<String> segments;
        final Handler handler;

        Route(String method, String pattern, Handler handler) {
            this.method = method;
            this.segments = split(pattern);
            this.handler = handler;
        }

        /** Returns captured params on a match, or null when the path does not fit. */
        Map<String, String> match(List<String> pathSegments) {
            if (pathSegments.size() != segments.size()) {
                return null;
            }
            Map<String, String> params = new LinkedHashMap<>();
            for (int i = 0; i < segments.size(); i++) {
                String patternSeg = segments.get(i);
                String actual = pathSegments.get(i);
                if (patternSeg.startsWith("{") && patternSeg.endsWith("}")) {
                    params.put(patternSeg.substring(1, patternSeg.length() - 1), actual);
                } else if (!patternSeg.equals(actual)) {
                    return null;
                }
            }
            return params;
        }
    }

    private final List<Route> routes = new ArrayList<>();
    private Handler fallback;

    public void add(String method, String pattern, Handler handler) {
        routes.add(new Route(method, pattern, handler));
    }

    public void get(String pattern, Handler handler) {
        add("GET", pattern, handler);
    }

    public void post(String pattern, Handler handler) {
        add("POST", pattern, handler);
    }

    public void put(String pattern, Handler handler) {
        add("PUT", pattern, handler);
    }

    public void delete(String pattern, Handler handler) {
        add("DELETE", pattern, handler);
    }

    /** Called when no route matches at all (for example, to serve static files). */
    public void fallback(Handler handler) {
        this.fallback = handler;
    }

    public int routeCount() {
        return routes.size();
    }

    public HttpResponse handle(HttpRequest request) {
        List<String> segments = request.pathSegments();
        Set<String> allowedMethods = new LinkedHashSet<>();
        for (Route route : routes) {
            Map<String, String> params = route.match(segments);
            if (params == null) {
                continue;
            }
            if (route.method.equals(request.method())) {
                return route.handler.handle(request, params);
            }
            allowedMethods.add(route.method);
        }
        if (!allowedMethods.isEmpty()) {
            return HttpResponse.methodNotAllowed(allowedMethods);
        }
        if (fallback != null) {
            return fallback.handle(request, Map.of());
        }
        return HttpResponse.notFound("no route for " + request.method() + " " + request.path());
    }

    private static List<String> split(String pattern) {
        List<String> segments = new ArrayList<>();
        for (String part : pattern.split("/")) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        return segments;
    }
}
