package com.minuu.httpserver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonTest {

    static void run(TestRunner r) {
        r.section("Json", () -> {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("name", "hello \"world\"");
            values.put("n", 42L);
            values.put("ok", true);
            values.put("nothing", null);
            values.put("list", List.of("a", 1L));
            String encoded = Json.object(values);
            r.eq("object encodes", "{\"name\":\"hello \\\"world\\\"\",\"n\":42,\"ok\":true,\"nothing\":null,\"list\":[\"a\",1]}", encoded);

            Map<String, Object> decoded = Json.parseObject(encoded);
            r.eq("round-trip string", "hello \"world\"", decoded.get("name"));
            r.eq("round-trip number", 42L, decoded.get("n"));
            r.eq("round-trip boolean", true, decoded.get("ok"));
            r.eq("round-trip null", null, decoded.get("nothing"));
            r.eq("round-trip list", List.of("a", 1L), decoded.get("list"));

            Map<String, Object> parsed = Json.parseObject("{\"key\":\"v\",\"ttl\":60,\"f\":1.5}");
            r.eq("parsed string", "v", parsed.get("key"));
            r.eq("parsed long", 60L, parsed.get("ttl"));
            r.eq("parsed double", 1.5, parsed.get("f"));

            r.eq("escapes newline", "\"a\\nb\"", Json.string("a\nb"));
            r.eq("escapes backslash", "\"a\\\\b\"", Json.string("a\\b"));
            r.eq("unicode escape parses", "A", Json.parseObject("{\"x\":\"\\u0041\"}").get("x"));

            expectBad(r, "{not json}", "malformed object rejected");
            expectBad(r, "{\"a\":}", "missing value rejected");
            expectBad(r, "{\"a\":\"unterminated}", "unterminated string rejected");
            expectBad(r, "[1,2", "unterminated array rejected");
        });
    }

    private static void expectBad(TestRunner r, String text, String name) {
        try {
            Json.parse(text);
            r.check(name, false);
        } catch (IllegalArgumentException e) {
            r.check(name, true);
        }
    }
}
