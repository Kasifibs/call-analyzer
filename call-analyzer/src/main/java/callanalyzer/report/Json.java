package callanalyzer.report;

import java.util.List;
import java.util.Map;

/** Minimal JSON writer for trees of {@link Map}, {@link List}, strings, numbers, booleans and {@code null}. */
final class Json {

    private Json() {
    }

    /** Indented JSON, as used by {@code --format json}. */
    static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, 0, true, false);
        return sb.toString();
    }

    /**
     * Compact JSON that is safe to embed in an HTML {@code <script>} block and is pure ASCII: {@code <} and all
     * non-ASCII characters are written as {@code \\uXXXX} escapes.
     */
    static String embeddable(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value, 0, false, true);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value, int indent, boolean pretty, boolean safe) {
        String nl = pretty ? "\n" : "";
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{').append(nl);
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (pretty) sb.append("  ".repeat(indent + 1));
                sb.append(str(String.valueOf(e.getKey()), safe)).append(pretty ? ": " : ":");
                append(sb, e.getValue(), indent + 1, pretty, safe);
                sb.append(++i < map.size() ? "," : "").append(nl);
            }
            if (pretty) sb.append("  ".repeat(indent));
            sb.append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[').append(nl);
            for (int i = 0; i < list.size(); i++) {
                if (pretty) sb.append("  ".repeat(indent + 1));
                append(sb, list.get(i), indent + 1, pretty, safe);
                sb.append(i < list.size() - 1 ? "," : "").append(nl);
            }
            if (pretty) sb.append("  ".repeat(indent));
            sb.append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(str(value == null ? null : value.toString(), safe));
        }
    }

    static String str(String s, boolean safe) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || safe && (c > 0x7e || c == '<' || c == '>' || c == '&')) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
