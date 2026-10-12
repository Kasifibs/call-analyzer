package callanalyzer.report;

import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.events.EventDispatch;
import callanalyzer.events.Registration;
import callanalyzer.model.MethodRef;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
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
                    + "  " + e.dispatchMethod().toShortString());
            out.println("     Event      : " + (e.eventClasses().isEmpty() ? "?" : e.eventClasses().stream()
                    .map(MethodRef::simpleName).collect(Collectors.joining(", ")))
                    + (e.eventNames().isEmpty() ? "" : "   ad: " + e.eventNames().stream()
                    .map(n -> "\"" + n + "\"").collect(Collectors.joining(", "))));
            out.println("     Secim      : " + resolutionText(e));
            out.println("     Observer'lar: " + (e.observers().isEmpty() ? "(dinleyen observer yok)"
                    : e.observers().stream().map(DispatchTarget::method).map(MethodRef::toShortString)
                    .collect(Collectors.joining(", "))));
        }
        out.flush();
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
            case BY_TYPE -> (e.eventNames().isEmpty() ? "" : "bu ada kayitli observer yok; ")
                    + "event tipini dinleyen observer'lar (addObserver / handle parametre tipi)";
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
        m.put("dispatches", result.events().stream().map(e -> {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("caller", e.caller().toQualifiedString());
            em.put("sourceFile", e.sourceFile());
            em.put("line", e.line());
            em.put("dispatchMethod", e.dispatchMethod().toQualifiedString());
            em.put("eventClasses", e.eventClasses().stream().map(MethodRef::toDottedName).toList());
            em.put("eventNames", e.eventNames());
            em.put("resolution", e.resolution().name());
            em.put("observers", e.observers().stream().map(t -> t.method().toQualifiedString()).toList());
            return (Object) em;
        }).toList());
        return m;
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
