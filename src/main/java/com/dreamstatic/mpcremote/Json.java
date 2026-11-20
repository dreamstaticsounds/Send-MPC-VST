package com.dreamstatic.mpcremote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader/writer covering what plugin.json and mpc-plugin.json need:
 * objects, arrays, strings (with escapes), integers, doubles, booleans and null.
 *
 * <p>Reading mirrors Python's {@code json.loads} closely enough for this tool: duplicate keys
 * keep the last value, numbers without a fraction/exponent become {@link Long}, everything else
 * becomes {@link Double}. Writing with {@link #write} mirrors {@code json.dump(..., indent=2,
 * ensure_ascii=False)}: two-space indent, {@code ", "} / {@code ": "} separators, raw UTF-8.</p>
 */
public final class Json {

    private Json() {
    }

    // ---------------------------------------------------------------- reading

    /** Parse a document; throws {@link JsonException} when it is not valid JSON. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWhitespace();
        if (!p.atEnd()) {
            throw p.error("unexpected trailing content");
        }
        return value;
    }

    /** Parse a document that must be a JSON object (or return null when it is not). */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** Convenience: get a string field, or null. */
    public static String str(Map<String, Object> obj, String key) {
        Object v = obj == null ? null : obj.get(key);
        return v instanceof String s ? s : null;
    }

    /** Convenience: get an int field, or the default. */
    public static int intValue(Map<String, Object> obj, String key, int dflt) {
        Object v = obj == null ? null : obj.get(key);
        if (v instanceof Long l) {
            return l.intValue();
        }
        if (v instanceof Double d) {
            return (int) Math.round(d);
        }
        return dflt;
    }

    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        JsonException error(String msg) {
            return new JsonException("line " + lineOf(pos) + ": " + msg);
        }

        private int lineOf(int at) {
            int line = 1;
            for (int i = 0; i < at && i < s.length(); i++) {
                if (s.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (atEnd() || s.charAt(pos) != c) {
                throw error("expected '" + c + "'");
            }
            pos++;
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expectWord("true");
                    return Boolean.TRUE;
                case 'f':
                    expectWord("false");
                    return Boolean.FALSE;
                case 'n':
                    expectWord("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private void expectWord(String word) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a string key");
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                out.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return out;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> out = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw error("unterminated escape");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"':
                            out.append('"');
                            break;
                        case '\\':
                            out.append('\\');
                            break;
                        case '/':
                            out.append('/');
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > s.length()) {
                                throw error("bad \\u escape");
                            }
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad \\u escape");
                            }
                            break;
                        default:
                            throw error("bad escape '\\" + e + "'");
                    }
                } else if (c < 0x20) {
                    throw error("unescaped control character in string");
                } else {
                    out.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                pos++;
            }
            boolean isDouble = false;
            if (!atEnd() && s.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            if (!atEnd() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (!atEnd() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                    pos++;
                }
                while (!atEnd() && Character.isDigit(s.charAt(pos))) {
                    pos++;
                }
            }
            String num = s.substring(start, pos);
            try {
                if (isDouble) {
                    return Double.parseDouble(num);
                }
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                throw error("invalid number '" + num + "'");
            }
        }
    }

    // ---------------------------------------------------------------- writing

    /** Serialize a value as compact JSON (no spaces). */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, -1, 0);
        return sb.toString();
    }

    /**
     * Serialize like {@code json.dump(value, f, indent=2, ensure_ascii=False)}:
     * two-space indent, {@code ",\\n"} between items, {@code ": "} after keys.
     */
    public static String writeIndented(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 2, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, int indent, int depth) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            sb.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new JsonException("cannot write non-finite number");
            }
            // Python writes integral floats with a ".0" (json.dumps(1.0) == "1.0")
            if (d == Math.floor(d) && Math.abs(d) < 1e16) {
                sb.append((long) d).append(".0");
            } else {
                sb.append(Double.toString(d));
            }
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            boolean pretty = indent >= 0;
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (pretty) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('\n');
                    sb.append(" ".repeat(indent * (depth + 1)));
                } else if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                writeValue(sb, e.getValue(), indent, depth + 1);
            }
            if (pretty) {
                sb.append('\n');
                sb.append(" ".repeat(indent * depth));
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> it) {
            boolean pretty = indent >= 0;
            List<Object> items = new ArrayList<>();
            it.forEach(items::add);
            if (items.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object item : items) {
                if (pretty) {
                    if (!first) {
                        sb.append(',');
                    }
                    sb.append('\n');
                    sb.append(" ".repeat(indent * (depth + 1)));
                } else if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item, indent, depth + 1);
            }
            if (pretty) {
                sb.append('\n');
                sb.append(" ".repeat(indent * depth));
            }
            sb.append(']');
        } else {
            throw new JsonException("cannot serialize " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c); // ensure_ascii=False: keep non-ASCII as-is
                    }
            }
        }
        sb.append('"');
    }
}
