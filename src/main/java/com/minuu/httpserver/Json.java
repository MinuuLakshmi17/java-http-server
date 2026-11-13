package com.minuu.httpserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON support, dependency-free.
 *
 * <p>The writer serializes {@code String}, {@code Number}, {@code Boolean},
 * {@code null}, {@code Map} and {@code List}. The parser is a small
 * recursive-descent parser for the same value set. It exists so the API
 * layer needs no third-party library; the server's only dependency is the
 * JDK itself.</p>
 */
public final class Json {

    private Json() {
    }

    /** Builds a JSON object from an insertion-ordered map. */
    public static String object(Map<String, Object> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(string(e.getKey())).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    public static String array(List<?> values) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object v : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(value(v));
        }
        return sb.append(']').toString();
    }

    public static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String s) {
            return string(s);
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, val) -> copy.put(String.valueOf(k), val));
            return object(copy);
        }
        if (v instanceof List<?> list) {
            return array(list);
        }
        return string(String.valueOf(v));
    }

    public static String string(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Parses a JSON value. Numbers become {@code Long} or {@code Double}. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object result = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw new IllegalArgumentException("trailing data after JSON value");
        }
        return result;
    }

    /** Parses a JSON object into a map. Throws {@code IllegalArgumentException} on bad input. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object result = parse(text);
        if (!(result instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) result;
    }

    private static final class Parser {
        private final String text;
        private int pos;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of JSON input");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // {
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated JSON object");
                }
                char c = text.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' in object");
                }
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("unterminated JSON array");
                }
                char c = text.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' in array");
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (!atEnd()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        break;
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > text.length()) {
                                throw new IllegalArgumentException("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("bad escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated JSON string");
        }

        Object parseLiteral(String literal, Object value) {
            if (!text.startsWith(literal, pos)) {
                throw new IllegalArgumentException("invalid literal at position " + pos);
            }
            pos += literal.length();
            return value;
        }

        Number parseNumber() {
            int start = pos;
            while (!atEnd() && "-+0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new IllegalArgumentException("invalid JSON value at position " + pos);
            }
            String num = text.substring(start, pos);
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                try {
                    return Double.parseDouble(num);
                } catch (NumberFormatException e2) {
                    throw new IllegalArgumentException("invalid number: " + num);
                }
            }
        }

        void expect(char c) {
            skipWhitespace();
            if (atEnd() || text.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at position " + pos);
            }
            pos++;
        }
    }
}
