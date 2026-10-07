package callanalyzer.report;

import callanalyzer.acquire.AcquireComparison;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.Finding;
import callanalyzer.model.MethodRef;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Machine readable report (for CI checks, further processing, etc.). */
public final class JsonReport {

    private JsonReport() {
    }

    public static void write(AnalysisResult result, PrintWriter out) {
        write(result, null, out);
    }

    public static void write(AnalysisResult result, AcquireComparison acquire, PrintWriter out) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", result.entries().stream().map(MethodRef::toQualifiedString).toList());
        root.put("targets", result.targets().stream().map(MethodRef::toDottedName).toList());
        root.put("analysedMethods", result.analysedMethods().size());
        root.put("warnings", result.warnings());
        root.put("findings", result.findings().stream().map(JsonReport::finding).toList());
        if (acquire != null) {
            root.put("acquire", AcquireReport.toJson(acquire));
        }
        StringBuilder sb = new StringBuilder();
        append(sb, root, 0);
        out.println(sb);
        out.flush();
    }

    private static Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targetType", MethodRef.toDottedName(f.targetType()));
        m.put("calledMethod", f.site().target().toQualifiedString());
        m.put("invokeKind", f.site().kind().name());
        m.put("viaLambda", f.site().viaLambda());
        m.put("caller", f.caller().toQualifiedString());
        m.put("sourceFile", f.callerSourceFile());
        m.put("line", f.site().line());
        m.put("depth", f.depth());
        m.put("receiverTypeMatches", f.receiverTypeMatches());
        m.put("interfaceMethod", f.interfaceMethod());
        m.put("internalCall", f.internalCall());
        m.put("implementations", f.matchedTargets().stream()
                .map(t -> {
                    Map<String, Object> i = new LinkedHashMap<>();
                    i.put("class", MethodRef.toDottedName(t.receiverClass()));
                    i.put("method", t.method().toQualifiedString());
                    return i;
                }).toList());
        m.put("chain", f.chain().stream()
                .map(s -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("method", s.method().toQualifiedString());
                    c.put("callLine", s.callLine());
                    return c;
                }).toList());
        return m;
    }

    private static void append(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append("  ".repeat(indent + 1)).append(str(String.valueOf(e.getKey()))).append(": ");
                append(sb, e.getValue(), indent + 1);
                sb.append(++i < map.size() ? ",\n" : "\n");
            }
            sb.append("  ".repeat(indent)).append('}');
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                sb.append("  ".repeat(indent + 1));
                append(sb, list.get(i), indent + 1);
                sb.append(i < list.size() - 1 ? ",\n" : "\n");
            }
            sb.append("  ".repeat(indent)).append(']');
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append(str(value == null ? null : value.toString()));
        }
    }

    static String str(String s) {
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
