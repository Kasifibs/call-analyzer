package callanalyzer.report;

import callanalyzer.acquire.AcquireCall;
import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.AcquiredCar;
import callanalyzer.acquire.ClassUsage;
import callanalyzer.analysis.Finding;
import callanalyzer.model.MethodRef;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Text and JSON rendering of an {@link AcquireComparison}. */
public final class AcquireReport {

    private static final int MAX_SITES = 5;

    private AcquireReport() {
    }

    public static void writeText(AcquireComparison c, PrintWriter out) {
        out.println();
        out.println("=== Acquire karşılaştırması: " + c.acquirer() + " ===");
        if (c.calls().isEmpty()) {
            out.println("UYARI: Akışta bu metoda çağrı bulunamadı; kullanılan tüm sınıflar eksik sayıldı.");
        }
        for (AcquireCall call : c.calls()) {
            out.println("Çağrı: " + call.caller().toShortString() + "  "
                    + TextReport.location(call.sourceFile(), call.line()));
            if (call.cars().isEmpty()) {
                out.println("    (listede hedef tipten eleman bulunamadı)");
            }
            for (AcquiredCar car : call.cars()) {
                String name = car.isResolved() ? MethodRef.simpleName(car.concreteClass()) : "?";
                out.println("    " + pad(name, 14) + " <- " + car.origin()
                        + (car.isResolved() ? "" : "  (sınıfı statik olarak belirlenemedi)"));
            }
        }
        out.println("Listedeki sınıflar   : " + simpleNames(c.acquired()));
        out.println("Akışta kullanılanlar : " + simpleNames(Stream.of(c.covered(), c.missing(), c.possiblyMissing(),
                c.undecided()).flatMap(List::stream).map(ClassUsage::className).sorted().toList()));

        section(out, "✗ EKSİK — akışta kesin olarak bu sınıfa çağrı var ama listede yok:", c.missing(), true);
        section(out, "? OLASI EKSİK — yalnızca arayüz üzerinden (ör. ICar) çağrılıyor olabilir, listede yok:",
                c.possiblyMissing(), false);
        if (!c.undecided().isEmpty()) {
            section(out, "~ BELİRSİZ — listede sınıfı belirlenemeyen eleman var ("
                    + c.unresolved().stream().map(AcquiredCar::origin).collect(Collectors.joining(", "))
                    + "); bu sınıflar o eleman olabilir:", c.undecided(), true);
        }
        if (!c.unused().isEmpty()) {
            out.println();
            out.println("! LİSTEDE OLUP AKIŞTA KULLANILMAYAN: " + simpleNames(c.unused()));
        }
        out.println();
        out.println("✓ Listede ve kullanılıyor: "
                + simpleNames(c.covered().stream().map(ClassUsage::className).toList()));
        out.flush();
    }

    private static void section(PrintWriter out, String title, List<ClassUsage> usages, boolean certainFirst) {
        if (usages.isEmpty()) {
            return;
        }
        out.println();
        out.println(title);
        for (ClassUsage u : usages) {
            out.println("  " + MethodRef.toDottedName(u.className()));
            List<Finding> sites = new ArrayList<>(certainFirst ? u.certain() : List.of());
            sites.addAll(u.possible());
            if (!certainFirst) {
                sites.addAll(0, u.certain());
            }
            for (Finding f : sites.subList(0, Math.min(MAX_SITES, sites.size()))) {
                out.println("      " + pad(f.site().target().toShortString(), 28) + " "
                        + f.caller().toShortString() + " " + TextReport.location(f.callerSourceFile(), f.site().line())
                        + (u.certain().contains(f) ? "" : "  [arayüz üzerinden]"));
            }
            if (sites.size() > MAX_SITES) {
                out.println("      ... ve " + (sites.size() - MAX_SITES) + " çağrı yeri daha");
            }
        }
    }

    static Map<String, Object> toJson(AcquireComparison c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acquirer", c.acquirer());
        m.put("calls", c.calls().stream().map(call -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("caller", call.caller().toQualifiedString());
            cm.put("sourceFile", call.sourceFile());
            cm.put("line", call.line());
            cm.put("cars", call.cars().stream().map(car -> {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("class", car.isResolved() ? MethodRef.toDottedName(car.concreteClass()) : null);
                e.put("declaredType", MethodRef.toDottedName(car.declaredType()));
                e.put("origin", car.origin());
                return e;
            }).toList());
            return cm;
        }).toList());
        m.put("acquired", dotted(c.acquired()));
        m.put("covered", usages(c.covered()));
        m.put("missing", usages(c.missing()));
        m.put("possiblyMissing", usages(c.possiblyMissing()));
        m.put("undecided", usages(c.undecided()));
        m.put("unused", dotted(c.unused()));
        return m;
    }

    private static List<Object> usages(List<ClassUsage> usages) {
        return usages.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("class", MethodRef.toDottedName(u.className()));
            m.put("certainCalls", sites(u.certain()));
            m.put("possibleCalls", sites(u.possible()));
            return (Object) m;
        }).toList();
    }

    private static List<Object> sites(List<Finding> findings) {
        return findings.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calledMethod", f.site().target().toQualifiedString());
            m.put("caller", f.caller().toQualifiedString());
            m.put("sourceFile", f.callerSourceFile());
            m.put("line", f.site().line());
            return (Object) m;
        }).toList();
    }

    private static List<String> dotted(List<String> names) {
        return names.stream().map(MethodRef::toDottedName).toList();
    }

    private static String simpleNames(List<String> names) {
        return names.isEmpty() ? "-" : names.stream().map(MethodRef::simpleName).collect(Collectors.joining(", "));
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
