package callanalyzer.report;

import callanalyzer.acquire.AcquireCall;
import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.AcquireRelease;
import callanalyzer.acquire.AcquiredCar;
import callanalyzer.acquire.AnnotationCheck;
import callanalyzer.acquire.ClassUsage;
import callanalyzer.acquire.ReleaseCheck;
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
        List<String> sources = new ArrayList<>();
        if (c.acquirer() != null) sources.add(c.acquirer());
        if (c.annotation() != null) sources.add("@" + MethodRef.toDottedName(c.annotation()));
        out.println("=== Acquire karsilastirmasi: " + String.join(", ", sources) + " ===");
        if (c.calls().isEmpty() && c.annotations().stream().noneMatch(AnnotationCheck::present)) {
            out.println("UYARI: Giris metodunda " + String.join(" ya da ", sources)
                    + " bulunamadi; kullanilan tum siniflar eksik sayildi.");
        }
        for (AnnotationCheck a : c.annotations()) {
            String name = "@" + MethodRef.simpleName(c.annotation());
            out.println("Anotasyon " + name + ": " + (a.present() ? "VAR" : "YOK") + "  "
                    + a.method().toShortString() + "  " + TextReport.location(a.sourceFile(), a.line()));
            if (a.present() && a.cars().isEmpty()) {
                out.println("    (anotasyonda sinif verilmemis)");
            }
            writeCars(out, a.cars());
        }
        for (AcquireCall call : c.calls()) {
            out.println("Cagri: " + call.caller().toShortString() + "  "
                    + TextReport.location(call.sourceFile(), call.line()));
            if (call.cars().isEmpty()) {
                out.println("    (listede hedef tipten eleman bulunamadi)");
            }
            writeCars(out, call.cars());
        }
        out.println("Acquire edilenler    : " + simpleNames(c.acquired()));
        out.println("Akista kullanilanlar : " + simpleNames(Stream.of(c.covered(), c.missing(), c.possiblyMissing(),
                c.undecided(), c.exempt()).flatMap(List::stream).map(ClassUsage::className).sorted().toList()));

        section(out, "[X] EKSIK - akista kesin olarak bu sinifa cagri var ama acquire edilmemis (listede/anotasyonda yok):", c.missing(), true);
        section(out, "? OLASI EKSIK - yalnizca arayuz uzerinden (or. ICar) cagriliyor olabilir, acquire edilmemis:",
                c.possiblyMissing(), false);
        if (!c.undecided().isEmpty()) {
            section(out, "~ BELIRSIZ - listede sinifi belirlenemeyen eleman var ("
                    + c.unresolved().stream().map(AcquiredCar::origin).collect(Collectors.joining(", "))
                    + "); bu siniflar o eleman olabilir:", c.undecided(), true);
        }
        section(out, "- ISTISNA - acquire edilmeden kullanilmasina ayarla izin verilmis, eksik sayilmadi:", c.exempt(),
                true);
        if (!c.unused().isEmpty()) {
            out.println();
            out.println("! ACQUIRE EDILIP AKISTA KULLANILMAYAN: " + simpleNames(c.unused()));
        }
        out.println();
        out.println("[OK] Acquire edilmis ve kullaniliyor: "
                + simpleNames(c.covered().stream().map(ClassUsage::className).toList()));
        if (!c.otherCalls().isEmpty()) {
            out.println();
            out.println("Bilgi: akistaki diger acquire cagrilari (giris metodunda degil, karsilastirmaya dahil edilmedi):");
            for (AcquireCall call : c.otherCalls()) {
                out.println("  Cagri: " + call.caller().toShortString() + "  "
                        + TextReport.location(call.sourceFile(), call.line()));
                writeCars(out, call.cars());
            }
        }
        if (c.release() != null) {
            writeRelease(c.release(), out);
        }
        out.flush();
    }

    private static void writeRelease(ReleaseCheck r, PrintWriter out) {
        out.println();
        out.println("RELEASE KONTROLU (" + r.releaser() + "): giris metodunda acquire edilen her eleman ayni metotta"
                + " finally icinde release edilmeli");
        if (r.elements().isEmpty()) {
            out.println("  (giris metodunda acquire cagrisi yok, kontrol edilecek bir sey yok)");
            return;
        }
        releaseGroup(out, "[R] RELEASE EDILMEMIS:", r.unreleased());
        releaseGroup(out, "! FINALLY DISINDA RELEASE EDILMIS (exception olursa release edilmez):", r.notInFinally());
        releaseGroup(out, "~ BELIRSIZ - release listesinde sinifi belirlenemeyen eleman var:", r.undecided());
        releaseGroup(out, "[OK] finally icinde release edilmis:", r.released());
    }

    private static void releaseGroup(PrintWriter out, String title, List<AcquireRelease> elements) {
        if (elements.isEmpty()) {
            return;
        }
        out.println(title);
        for (AcquireRelease e : elements) {
            String name = e.car().isResolved() ? MethodRef.simpleName(e.car().concreteClass()) : "?";
            out.println("  " + pad(name, 14) + " acquire: " + e.caller().toShortString() + " "
                    + TextReport.location(e.sourceFile(), e.acquireLine())
                    + (e.release() == null ? "" : "  release: satir " + e.release().line())
                    + (e.car().isResolved() ? "" : "  (" + e.car().origin() + ")"));
        }
    }

    private static void writeCars(PrintWriter out, List<AcquiredCar> cars) {
        for (AcquiredCar car : cars) {
            String name = car.isResolved() ? MethodRef.simpleName(car.concreteClass()) : "?";
            out.println("    " + pad(name, 14) + " <- " + car.origin()
                    + (car.isResolved() ? "" : "  (sinifi statik olarak belirlenemedi)"));
        }
    }

    private static void section(PrintWriter out, String title, List<ClassUsage> usages, boolean certainFirst) {
        if (usages.isEmpty()) {
            return;
        }
        out.println();
        out.println(title);
        for (ClassUsage u : usages) {
            boolean onlyAssumed = Stream.concat(u.certain().stream(), u.possible().stream())
                    .allMatch(Finding::viaAssumedEvent);
            out.println("  " + MethodRef.toDottedName(u.className())
                    + (onlyAssumed ? "   (yalnizca event varsayimi uzerinden)" : ""));
            List<Finding> sites = new ArrayList<>(certainFirst ? u.certain() : List.of());
            sites.addAll(u.possible());
            if (!certainFirst) {
                sites.addAll(0, u.certain());
            }
            for (Finding f : sites.subList(0, Math.min(MAX_SITES, sites.size()))) {
                out.println("      " + pad(f.site().target().toShortString(), 28) + " "
                        + f.caller().toShortString() + " " + TextReport.location(f.callerSourceFile(), f.site().line())
                        + (u.certain().contains(f) ? "" : "  [arayuz uzerinden]")
                        + (f.viaAssumedEvent() ? "  [event varsayimi]" : ""));
            }
            if (sites.size() > MAX_SITES) {
                out.println("      ... ve " + (sites.size() - MAX_SITES) + " cagri yeri daha");
            }
        }
    }

    static Map<String, Object> toJson(AcquireComparison c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acquirer", c.acquirer());
        m.put("annotation", c.annotation() == null ? null : MethodRef.toDottedName(c.annotation()));
        m.put("annotations", c.annotations().stream().map(a -> {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("method", a.method().toQualifiedString());
            am.put("present", a.present());
            am.put("cars", cars(a.cars()));
            return am;
        }).toList());
        m.put("calls", calls(c.calls()));
        m.put("otherCalls", calls(c.otherCalls()));
        m.put("acquired", dotted(c.acquired()));
        m.put("covered", usages(c.covered()));
        m.put("missing", usages(c.missing()));
        m.put("possiblyMissing", usages(c.possiblyMissing()));
        m.put("undecided", usages(c.undecided()));
        m.put("exempt", usages(c.exempt()));
        m.put("unused", dotted(c.unused()));
        if (c.release() != null) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("releaser", c.release().releaser());
            rm.put("calls", c.release().calls().stream().map(call -> {
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("caller", call.caller().toQualifiedString());
                cm.put("sourceFile", call.sourceFile());
                cm.put("line", call.line());
                cm.put("inFinally", call.inFinally());
                cm.put("cars", cars(call.cars()));
                return (Object) cm;
            }).toList());
            rm.put("elements", c.release().elements().stream().map(e -> {
                Map<String, Object> em = new LinkedHashMap<>();
                em.put("status", e.status().name());
                em.put("class", e.car().isResolved() ? MethodRef.toDottedName(e.car().concreteClass()) : null);
                em.put("origin", e.car().origin());
                em.put("caller", e.caller().toQualifiedString());
                em.put("sourceFile", e.sourceFile());
                em.put("acquireLine", e.acquireLine());
                em.put("releaseLine", e.release() == null ? null : e.release().line());
                return (Object) em;
            }).toList());
            m.put("release", rm);
        }
        return m;
    }

    private static List<Object> calls(List<AcquireCall> calls) {
        return calls.stream().map(call -> {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("caller", call.caller().toQualifiedString());
            cm.put("sourceFile", call.sourceFile());
            cm.put("line", call.line());
            cm.put("cars", cars(call.cars()));
            return (Object) cm;
        }).toList();
    }

    private static List<Object> cars(List<AcquiredCar> cars) {
        return cars.stream().map(car -> {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("class", car.isResolved() ? MethodRef.toDottedName(car.concreteClass()) : null);
            e.put("declaredType", MethodRef.toDottedName(car.declaredType()));
            e.put("origin", car.origin());
            return (Object) e;
        }).toList();
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
            m.put("viaAssumedEvent", f.viaAssumedEvent());
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
