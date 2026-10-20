package callanalyzer.report;

import callanalyzer.acquire.AcquireCall;
import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.AcquiredCar;
import callanalyzer.acquire.ClassUsage;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallEdge;
import callanalyzer.analysis.ChainStep;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.analysis.Finding;
import callanalyzer.events.EventDispatch;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single, self-contained HTML page (no external resources) for browsing an analysis: summary, findings with their
 * call chains, a lazily expanded call tree, the acquire comparison and the events. The analysis data is embedded as
 * JSON; the page template is {@code /report/report.html} (styles in {@code report.css}). The file is pure ASCII, so it displays correctly whatever
 * encoding the browser assumes.
 */
public final class HtmlReport {

    private final AnalysisResult result;
    private final ClassHierarchy h;
    private final Map<MethodRef, Integer> ids = new LinkedHashMap<>();
    private final List<Map<String, Object>> methods = new ArrayList<>();
    private final Map<Finding, Integer> findingIndex = new IdentityHashMap<>();
    private final Map<List<Object>, List<Integer>> findingsBySite = new HashMap<>();

    private HtmlReport(AnalysisResult result) {
        this.result = result;
        this.h = result.hierarchy();
    }

    public static void write(AnalysisResult result, AcquireComparison acquire, PrintWriter out) {
        write(result, acquire, null, out);
    }

    /** @param backLink relative link to an overview page (scan mode), or {@code null} */
    public static void write(AnalysisResult result, AcquireComparison acquire, String backLink, PrintWriter out) {
        Map<String, Object> data = new HtmlReport(result).data(acquire);
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) data.get("meta");
        meta.put("back", backLink);
        page("report.html", data, out);
    }

    /** Fills {@code /report/<template>} with the shared stylesheet and the embedded data. */
    static void page(String templateName, Object data, PrintWriter out) {
        String template = resource(templateName).replace("/*CSS*/", resource("report.css"));
        int at = template.indexOf("/*DATA*/");
        out.print(asciiSafe(template.substring(0, at)) + Json.embeddable(data)
                + asciiSafe(template.substring(at + "/*DATA*/".length())));
        out.flush();
    }

    private Map<String, Object> data(AcquireComparison acquire) {
        List<Finding> findings = result.findings();
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            findingIndex.put(f, i);
            findingsBySite.computeIfAbsent(List.of(f.caller(), f.site()), k -> new ArrayList<>()).add(i);
        }
        result.edges().keySet().forEach(this::id);

        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("entries", result.entries().stream().map(this::id).toList());
        meta.put("targets", result.targets().stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", MethodRef.toDottedName(t));
            m.put("impls", h.allSubtypes(t).stream().filter(s -> !s.equals(t))
                    .filter(s -> h.get(s) != null && h.get(s).isConcrete()).map(MethodRef::simpleName).toList());
            return m;
        }).toList());
        meta.put("analysed", result.analysedMethods().size());
        meta.put("maxDepth", result.depths().values().stream().mapToInt(Integer::intValue).max().orElse(0));
        meta.put("warnings", result.warnings());
        meta.put("generated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        meta.put("events", !result.events().isEmpty() || !result.eventRegistrations().isEmpty());
        root.put("meta", meta);

        Map<String, Object> edges = new LinkedHashMap<>();
        for (Map.Entry<MethodRef, List<CallEdge>> e : result.edges().entrySet()) {
            edges.put(String.valueOf(id(e.getKey())), calls(e.getKey(), e.getValue()));
        }
        root.put("edges", edges);
        root.put("findings", findings.stream().map(this::finding).toList());
        root.put("events", events());
        root.put("acquire", acquire == null ? null : acquire(acquire));
        root.put("methods", methods); // last: every method referenced above has an id by now
        return root;
    }

    private int id(MethodRef ref) {
        Integer existing = ids.get(ref);
        if (existing != null) {
            return existing;
        }
        int id = ids.size();
        ids.put(ref, id);
        ClassInfo cls = h.get(ref.owner());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", ref.toShortString());
        m.put("q", ref.toQualifiedString());
        m.put("f", cls == null ? null : cls.sourceFile());
        m.put("a", result.edges().containsKey(ref));
        m.put("d", result.depths().get(ref));
        methods.add(m);
        return id;
    }

    private List<Object> calls(MethodRef caller, List<CallEdge> edges) {
        Map<List<Object>, String> eventLabels = new HashMap<>();
        for (EventDispatch e : result.events()) {
            if (e.caller().equals(caller)) {
                eventLabels.put(List.of(e.dispatchMethod(), e.line()), e.label());
            }
        }
        List<Object> calls = new ArrayList<>();
        for (CallEdge edge : edges) {
            List<Integer> hits = findingsBySite.getOrDefault(List.of(caller, edge.site()), List.of());
            String event = eventLabels.get(List.of(edge.site().target(), edge.site().line()));
            boolean leadsSomewhere = edge.targets().stream().anyMatch(t ->
                    h.method(t.method()).map(MethodInfo::hasBody).orElse(false));
            if (hits.isEmpty() && event == null && !leadsSomewhere) {
                continue; // library calls such as System.out.println
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("c", edge.site().target().toShortString());
            c.put("l", edge.site().line());
            c.put("k", edge.site().kind().name());
            c.put("la", edge.site().viaLambda());
            c.put("h", hits);
            c.put("e", event);
            c.put("sg", edge.targets().size() == 1 && edge.targets().get(0).method().equals(edge.site().target()));
            c.put("t", edge.targets().stream().map(t -> List.of(MethodRef.simpleName(t.receiverClass()),
                    id(t.method()))).toList());
            calls.add(c);
        }
        return calls;
    }

    private Map<String, Object> finding(Finding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tg", MethodRef.simpleName(f.targetType()));
        m.put("c", f.site().target().toShortString());
        m.put("cq", f.site().target().toQualifiedString());
        m.put("by", id(f.caller()));
        m.put("f", f.callerSourceFile());
        m.put("l", f.site().line());
        m.put("d", f.depth());
        m.put("k", f.site().kind().name());
        m.put("la", f.site().viaLambda());
        m.put("rt", f.receiverTypeMatches());
        m.put("im", f.interfaceMethod());
        m.put("in", f.internalCall());
        m.put("r", f.matchedTargets().stream().map(t -> List.of(MethodRef.simpleName(t.receiverClass()),
                t.method().toShortString())).toList());
        m.put("ch", chain(f.chain()));
        return m;
    }

    private List<Object> chain(List<ChainStep> chain) {
        List<Object> steps = new ArrayList<>();
        for (ChainStep s : chain) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id(s.method()));
            m.put("l", s.callLine());
            m.put("v", s.via());
            steps.add(m);
        }
        return steps;
    }

    private Map<String, Object> events() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registrations", result.eventRegistrations().stream().map(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("name", r.eventName());
            rm.put("type", r.eventType() == null ? null : MethodRef.simpleName(r.eventType()));
            rm.put("observer", MethodRef.simpleName(r.observerClass()));
            rm.put("in", r.registeredIn().toShortString());
            rm.put("f", r.sourceFile());
            rm.put("l", r.line());
            return (Object) rm;
        }).toList());
        m.put("dispatches", result.events().stream().map(e -> {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("by", id(e.caller()));
            em.put("f", e.sourceFile());
            em.put("l", e.line());
            em.put("dm", e.dispatchMethod().toShortString());
            em.put("cls", e.eventClasses().stream().map(MethodRef::simpleName).toList());
            em.put("names", e.eventNames());
            Map<String, Object> origins = new LinkedHashMap<>();
            e.eventOrigins().forEach((cls, list) -> origins.put(cls.equals("?") ? "?" : MethodRef.simpleName(cls), list));
            em.put("origins", origins);
            em.put("res", e.resolution().name());
            em.put("obs", e.observers().stream()
                    .map(t -> List.of(MethodRef.simpleName(t.receiverClass()), id(t.method()))).toList());
            return (Object) em;
        }).toList());
        return m;
    }

    private Map<String, Object> acquire(AcquireComparison c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acquirer", c.acquirer());
        m.put("annotation", c.annotation() == null ? null : MethodRef.simpleName(c.annotation()));
        m.put("annotations", c.annotations().stream().map(a -> {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("m", a.method().toShortString());
            am.put("f", a.sourceFile());
            am.put("l", a.line());
            am.put("present", a.present());
            am.put("cars", cars(a.cars()));
            return (Object) am;
        }).toList());
        m.put("calls", acquireCalls(c.calls()));
        m.put("other", acquireCalls(c.otherCalls()));
        m.put("acquired", c.acquired().stream().map(MethodRef::simpleName).toList());
        m.put("unresolved", c.unresolved().stream().map(AcquiredCar::origin).toList());
        m.put("missing", usages(c.missing()));
        m.put("possiblyMissing", usages(c.possiblyMissing()));
        m.put("undecided", usages(c.undecided()));
        m.put("covered", usages(c.covered()));
        m.put("unused", c.unused().stream().map(MethodRef::simpleName).toList());
        return m;
    }

    private List<Object> acquireCalls(List<AcquireCall> calls) {
        return calls.stream().map(call -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("m", call.caller().toShortString());
            cm.put("f", call.sourceFile());
            cm.put("l", call.line());
            cm.put("cars", cars(call.cars()));
            return (Object) cm;
        }).toList();
    }

    private static List<Object> cars(List<AcquiredCar> cars) {
        return cars.stream().map(car -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("cls", car.isResolved() ? MethodRef.simpleName(car.concreteClass()) : null);
            e.put("origin", car.origin());
            return (Object) e;
        }).toList();
    }

    private List<Object> usages(List<ClassUsage> usages) {
        return usages.stream().map(u -> {
            Map<String, Object> um = new LinkedHashMap<>();
            um.put("cls", MethodRef.simpleName(u.className()));
            um.put("q", MethodRef.toDottedName(u.className()));
            um.put("certain", u.certain().stream().map(findingIndex::get).toList());
            um.put("possible", u.possible().stream().map(findingIndex::get).toList());
            return (Object) um;
        }).toList();
    }

    private static String resource(String name) {
        try (InputStream in = HtmlReport.class.getResourceAsStream("/report/" + name)) {
            if (in == null) {
                throw new IllegalStateException("report/" + name + " bulunamadi");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Makes the template pure ASCII: inside {@code <script>} non-ASCII characters become {@code \\uXXXX} (they only
     * occur in string literals and comments there), elsewhere they become HTML character references.
     */
    static String asciiSafe(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inScript = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.startsWith("<script", i)) {
                inScript = true;
            } else if (s.startsWith("</script", i)) {
                inScript = false;
            }
            char c = s.charAt(i);
            if (c < 0x80) {
                sb.append(c);
            } else if (inScript) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append("&#").append((int) c).append(';');
            }
        }
        return sb.toString();
    }
}
