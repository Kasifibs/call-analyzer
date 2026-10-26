package callanalyzer.report;

import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.AcquireRelease;
import callanalyzer.acquire.ClassUsage;
import callanalyzer.model.MethodRef;
import callanalyzer.scan.ScanItem;
import callanalyzer.scan.ScanItem.Status;
import callanalyzer.scan.ScanResult;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Text and JSON output of an acquire scan ({@code --scan}). */
public final class ScanReport {

    private ScanReport() {
    }

    public static void writeText(ScanResult scan, PrintWriter out) {
        out.println("=== Toplu acquire taramasi: " + sources(scan) + " ===");
        long calls = scan.items().stream().filter(i -> i.candidate().callsAcquirer()).count();
        long annotated = scan.items().stream().filter(i -> i.candidate().annotated()).count();
        out.println("Taranan metot : " + scan.items().size() + "  (acquire cagrisi: " + calls
                + (scan.annotation() == null ? "" : ", @" + MethodRef.simpleName(scan.annotation()) + ": " + annotated) + ")");
        out.println("Sonuc         : " + scan.count(Status.MISSING) + " metotta kesin eksik, "
                + (scan.count(Status.UNRELEASED) > 0 ? scan.count(Status.UNRELEASED) + " metotta release eksik, " : "")
                + scan.count(Status.POSSIBLE) + " metotta yalnizca olasi eksik, " + scan.count(Status.OK) + " metot OK"
                + (scan.count(Status.ERROR) > 0 ? ", " + scan.count(Status.ERROR) + " hata" : ""));
        if (scan.items().isEmpty()) {
            out.println("Acquire eden metot bulunamadi.");
            out.flush();
            return;
        }
        out.println();
        out.println(String.format("%4s  %-6s %-22s %-22s %-18s %-14s %s", "#", "Durum", "Kesin eksik",
                "Olasi eksik/belirsiz", "Kullanilmayan", "Istisna", "Metot"));
        for (ScanItem item : scan.items()) {
            AcquireComparison c = item.comparison();
            String possible = c == null ? "-" : join(names(c.possiblyMissing()),
                    c.undecided().isEmpty() ? "-" : String.join(",", simple(c.undecided())) + "(?)");
            out.println(String.format("%4d  %-6s %-22s %-22s %-18s %-14s %s%s", item.index(),
                    label(item.status()), c == null ? "-" : names(c.missing()), possible,
                    c == null || c.unused().isEmpty() ? "-" : String.join(",", c.unused().stream()
                            .map(MethodRef::simpleName).toList()),
                    c == null ? "-" : names(c.exempt()),
                    item.candidate().method().toQualifiedString(), source(item)));
        }
        for (ScanItem item : scan.items()) {
            if (item.status() == Status.OK) {
                continue; // the table says everything about these
            }
            out.println();
            out.println("#".repeat(100));
            out.println("[" + item.index() + "] " + item.candidate().method().toQualifiedString() + "   "
                    + label(item.status()) + "   (" + item.findings() + " bulgu, " + item.analysedMethods()
                    + " metot incelendi)");
            if (item.error() != null) {
                out.println("HATA: " + item.error());
            } else {
                AcquireReport.writeText(item.comparison(), out);
            }
        }
        out.println();
        out.println("Ayrintili akis icin tek metot analizi (--entry) ya da --format html kullanin.");
        out.flush();
    }

    public static void writeJson(ScanResult scan, PrintWriter out) {
        out.println(Json.pretty(toJson(scan, null)));
        out.flush();
    }

    /** @param pages relative page link per item index (HTML scan), or {@code null} */
    static Map<String, Object> toJson(ScanResult scan, Map<Integer, String> pages) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("acquirer", scan.acquirer());
        root.put("annotation", scan.annotation() == null ? null : MethodRef.toDottedName(scan.annotation()));
        root.put("annotationShort", scan.annotation() == null ? null : MethodRef.simpleName(scan.annotation()));
        Map<String, Object> summary = new LinkedHashMap<>();
        for (Status s : Status.values()) {
            summary.put(s.name(), scan.count(s));
        }
        root.put("summary", summary);
        root.put("methods", scan.items().stream().map(item -> {
            Map<String, Object> m = new LinkedHashMap<>();
            AcquireComparison c = item.comparison();
            m.put("index", item.index());
            m.put("method", item.candidate().method().toQualifiedString());
            m.put("callsAcquirer", item.candidate().callsAcquirer());
            m.put("annotated", item.candidate().annotated());
            m.put("status", item.status().name());
            m.put("findings", item.findings());
            m.put("analysedMethods", item.analysedMethods());
            m.put("error", item.error());
            if (c != null) {
                m.put("acquired", c.acquired().stream().map(MethodRef::simpleName).toList());
                m.put("missing", simple(c.missing()));
                m.put("possiblyMissing", simple(c.possiblyMissing()));
                m.put("undecided", simple(c.undecided()));
                m.put("exempt", simple(c.exempt()));
                if (c.release() != null) {
                    m.put("unreleased", released(c.release().unreleased()));
                    m.put("releaseWarnings", released(Stream.concat(c.release().notInFinally().stream(),
                            c.release().undecided().stream()).toList()));
                }
                m.put("unused", c.unused().stream().map(MethodRef::simpleName).toList());
                if (pages == null) {
                    m.put("acquire", AcquireReport.toJson(c));
                }
            }
            if (pages != null) {
                m.put("page", pages.get(item.index()));
            }
            return (Object) m;
        }).toList());
        return root;
    }

    private static List<String> simple(List<ClassUsage> usages) {
        return usages.stream().map(u -> MethodRef.simpleName(u.className())).toList();
    }

    private static List<String> released(List<AcquireRelease> elements) {
        return elements.stream().map(e -> e.car().isResolved() ? MethodRef.simpleName(e.car().concreteClass()) : "?")
                .distinct().toList();
    }

    private static String names(List<ClassUsage> usages) {
        return usages.isEmpty() ? "-" : String.join(",", simple(usages));
    }

    private static String join(String a, String b) {
        return a.equals("-") ? b : b.equals("-") ? a : a + "," + b;
    }

    private static String label(Status s) {
        return switch (s) {
            case MISSING -> "[X]";
            case UNRELEASED -> "[R]";
            case POSSIBLE -> "[?]";
            case OK -> "[OK]";
            case ERROR -> "[HATA]";
        };
    }

    private static String source(ScanItem item) {
        StringBuilder sb = new StringBuilder();
        if (item.candidate().callsAcquirer()) sb.append("  [acquire]");
        if (item.candidate().annotated()) sb.append("  [anotasyon]");
        return sb.toString();
    }

    private static String sources(ScanResult scan) {
        StringBuilder sb = new StringBuilder();
        if (scan.acquirer() != null) sb.append(scan.acquirer());
        if (scan.annotation() != null) {
            sb.append(sb.length() > 0 ? ", " : "").append('@').append(MethodRef.toDottedName(scan.annotation()));
        }
        return sb.toString();
    }
}
