package com.dreamstatic.mpcremote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesObjectsArraysAndScalars() {
        Map<String, Object> m = Json.parseObject("{\"name\":\"X\",\"n\":3,\"f\":1.5,\"b\":true,\"z\":null}");
        assertEquals("X", m.get("name"));
        assertEquals(3L, m.get("n"));
        assertEquals(1.5, (Double) m.get("f"), 1e-9);
        assertEquals(Boolean.TRUE, m.get("b"));
        assertNull(m.get("z"));
        List<?> arr = (List<?>) Json.parse("[1, \"two\", [3]]");
        assertEquals(3, arr.size());
        assertEquals("two", arr.get(1));
    }

    @Test
    void parsesEscapesAndUnicode() {
        Map<String, Object> m = Json.parseObject("{\"s\":\"a\\\"b\\n\\t\\\\\\u00e9\\ud83d\\ude00\"}");
        assertEquals("a\"b\n\t\\é😀", m.get("s"));
    }

    @Test
    void duplicateKeysKeepTheLastValue() {
        Map<String, Object> m = Json.parseObject("{\"a\":1,\"a\":2}");
        assertEquals(2L, m.get("a"));
    }

    @Test
    void rejectsInvalidDocuments() {
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":1} extra"));
        assertThrows(Json.JsonException.class, () -> Json.parse("{\"a\":"));
        assertThrows(Json.JsonException.class, () -> Json.parse("[1,]"));
        assertThrows(Json.JsonException.class, () -> Json.parse("\"unterminated"));
        assertThrows(Json.JsonException.class, () -> Json.parse("nul"));
        // a top-level array is valid JSON but not an object
        assertNull(Json.parseObject("[1,2]"));
    }

    @Test
    void writesIndentedLikePythonJsonDump() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "MPC Plaits");
        m.put("numInputs", 0);
        m.put("file64", "plaits.so");
        m.put("list", List.of("a", "b"));
        m.put("nested", Map.of("k", 1));
        String expected = "{\n"
                + "  \"name\": \"MPC Plaits\",\n"
                + "  \"numInputs\": 0,\n"
                + "  \"file64\": \"plaits.so\",\n"
                + "  \"list\": [\n"
                + "    \"a\",\n"
                + "    \"b\"\n"
                + "  ],\n"
                + "  \"nested\": {\n"
                + "    \"k\": 1\n"
                + "  }\n"
                + "}";
        assertEquals(expected, Json.writeIndented(m));
    }

    @Test
    void writesCompactAndKeepsNonAsciiRaw() {
        assertEquals("{\"a\":\"é\"}", Json.write(Map.of("a", "é")));
        assertEquals("[]", Json.write(List.of()));
        assertEquals("{}", Json.write(Map.of()));
        assertEquals("1.0", Json.write(1.0));
        assertEquals("\"line\\n\"", Json.write("line\n"));
    }

    @Test
    void roundTripsThroughBothDirections() {
        String src = "{\"a\":[1,2,{\"b\":\"c\"}],\"d\":-3.25,\"e\":false}";
        Object parsed = Json.parse(src);
        assertEquals(parsed, Json.parse(Json.write(parsed)));
        assertEquals(parsed, Json.parse(Json.writeIndented(parsed)));
        assertInstanceOf(Map.class, parsed);
    }
}
