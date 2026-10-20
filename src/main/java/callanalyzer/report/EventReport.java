package callanalyzer.report;

import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.events.EventDispatch;
import callanalyzer.events.Registration;
import callanalyzer.model.MethodRef;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Text and JSON rendering of the events followed during the analysis. */
public final class EventReport {

    private EventReport() {
    }

    public static void writeText(AnalysisResult result, PrintWriter out) {
        out.println();
        out.println("=== Event akisi ===");
        writePublished(result, out);
        Map<String, TreeSet<String>> byName = new TreeMap<>();
        for (Registration r : result.eventRegistrations()) {
            byName.computeIfAbsent(key(r), k -> new TreeSet<>()).add(MethodRef.simpleName(r.observerClass()));
        }
        out.println("Kayitli observer'lar (" + result.eventRegistrations().size() + " kayit):");
        byName.forEach((name, observers) -> out.println("    " + pad(name, 24) + " -> " + String.join(", ", observers)));
        if (result.events().isEmpty()) {
            out.println("Akista event yayinlanmadi.");
        }
        int i = 1;
        for (EventDispatch e : result.events()) {
            out.println();
            out.println("[E" + i++ + "] " + e.caller().toShortString() + "  " + TextReport.location(e.sourceFile(), e.line())
                    + "  " + e.publication());
            out.println("     Event      : " + (e.eventClasses().isEmpty() ? "?" : e.eventClasses().stream()
                    .map(MethodRef::simpleName).collect(Collectors.joining(", ")))
                    + (e.eventNames().isEmpty() ? "" : "   ad: " + e.eventNames().stream()
                    .map(n -> "\"" + n + "\"").collect(Collectors.joining(", "))));
            e.eventOrigins().forEach((cls, list) -> list.forEach(o -> out.println("     Kaynak     : "
                    + (cls.equals("?") ? "" : MethodRef.simpleName(cls) + " <- ") + o)));
            out.println("     Yayin      : " + publicationText(e));
            out.println("     Secim      : " + resolutionText(e));
            out.println("     Observer'lar: " + (e.observers().isEmpty() ? "(dinleyen observer yok)"
                    : e.observers().stream().map(EventReport::observerLabel).collect(Collectors.joining(", "))));
        }
        out.flush();
    }

    /** Event class (internal name, or "?" for dispatches whose event is unknown) to the dispatches publishing it. */
    static Map<String, List<Integer>> published(AnalysisResult result) {
        Map<String, List<Integer>> map = new TreeMap<>((a, b) -> a.equals(b) ? 0 : a.equals("?") ? 1 : b.equals("?") ? -1
                : MethodRef.simpleName(a).compareTo(MethodRef.simpleName(b)) != 0
                ? MethodRef.simpleName(a).compareTo(MethodRef.simpleName(b)) : a.compareTo(b));
        for (int i = 0; i < result.events().size(); i++) {
            EventDispatch e = result.events().get(i);
            if (e.eventClasses().isEmpty() || e.resolution() == EventDispatch.Resolution.ALL) {
                map.computeIfAbsent("?", k -> new java.util.ArrayList<>()).add(i);
            }
            for (String cls : e.eventClasses()) {
                map.computeIfAbsent(cls, k -> new java.util.ArrayList<>()).add(i);
            }
        }
        return map;
    }

    private static void writePublished(AnalysisResult result, PrintWriter out) {
        Map<String, List<Integer>> published = published(result);
        out.println("Akista yayinlanan event'ler (" + published.keySet().stream().filter(k -> !k.equals("?")).count()
                + " tip):");
        if (published.isEmpty()) {
            out.println("    (akista event yayinlanmiyor)");
        }
        published.forEach((cls, dispatches) -> {
            boolean unknown = cls.equals("?");
            for (int k = 0; k < dispatches.size(); k++) {
                EventDispatch e = result.events().get(dispatches.get(k));
                String head = k == 0 ? (unknown ? "? (belirlenemedi)" : MethodRef.simpleName(cls)) : "";
                out.println("    " + pad(head, 24) + " E" + (dispatches.get(k) + 1) + "  " + e.caller().toShortString()
                        + "  " + TextReport.location(e.sourceFile(), e.line()));
                List<String> origins = e.eventOrigins().getOrDefault(cls, List.of());
                for (String o : origins) {
                    out.println("    " + pad("", 24) + "     <- " + o);
                }
                out.println("    " + pad("", 24) + "     yayin: " + publicationText(e));
            }
        });
        out.println();
    }

    /** {@code SpareTyreObserver: BaseHandler#handle(Event)} when the handle method is inherited. */
    static String observerLabel(DispatchTarget t) {
        String method = t.method().toShortString();
        return t.method().owner().equals(t.receiverClass()) ? method
                : MethodRef.simpleName(t.receiverClass()) + ": " + method;
    }

    static String publicationText(EventDispatch e) {
        if (e.kind() == EventDispatch.Kind.CREATION) {
            return e.confirmations().isEmpty()
                    ? "DOGRULANAMADI - new ile olusturuldu, yayinlandigi varsayildi (notify cagrisi bulunamadi)"
                    : "notify ile dogrulandi (" + String.join(", ", e.confirmations()) + ")";
        }
        return e.assumed() ? "event belirlenemedi (varsayim)" : "dispatcher cagrisi";
    }

    static String key(Registration r) {
        if (r.eventName() != null) {
            return r.eventName();
        }
        return r.eventType() != null ? "tip: " + MethodRef.simpleName(r.eventType()) : "(ad bilinmiyor)";
    }

    private static String resolutionText(EventDispatch e) {
        return switch (e.resolution()) {
            case BY_NAME -> "event adina kayitli observer'lar";
            case BY_TYPE -> "event tipini dinleyen observer'lar (addObserver / handle parametre tipi)";
            case ALL -> "event belirlenemedi; kayitli tum observer'lar (ihtiyatli)";
        };
    }

    static Map<String, Object> toJson(AnalysisResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("registrations", result.eventRegistrations().stream().map(r -> {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("eventName", r.eventName());
            rm.put("eventType", r.eventType() == null ? null : MethodRef.toDottedName(r.eventType()));
            rm.put("observer", MethodRef.toDottedName(r.observerClass()));
            rm.put("registeredIn", r.registeredIn().toQualifiedString());
            rm.put("sourceFile", r.sourceFile());
            rm.put("line", r.line());
            return (Object) rm;
        }).toList());
        Map<String, Object> published = new LinkedHashMap<>();
        published(result).forEach((cls, ids) -> published.put(cls.equals("?") ? "?" : MethodRef.toDottedName(cls),
                ids.stream().map(i -> i + 1).toList()));
        m.put("published", published);
        m.put("dispatches", result.events().stream().map(e -> {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("caller", e.caller().toQualifiedString());
            em.put("sourceFile", e.sourceFile());
            em.put("line", e.line());
            em.put("dispatchMethod", e.dispatchMethod().toQualifiedString());
            em.put("eventClasses", e.eventClasses().stream().map(MethodRef::toDottedName).toList());
            em.put("eventNames", e.eventNames());
            em.put("resolution", e.resolution().name());
            em.put("kind", e.kind().name());
            em.put("confirmations", e.confirmations());
            em.put("assumed", e.assumed());
            em.put("observers", e.observers().stream().map(t -> MethodRef.toDottedName(t.receiverClass()) + ": "
                    + t.method().toQualifiedString()).toList());
            return (Object) em;
        }).toList());
        return m;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
