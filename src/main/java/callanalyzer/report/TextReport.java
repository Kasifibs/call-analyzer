package callanalyzer.report;

import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.ChainStep;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.analysis.Finding;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodRef;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Human readable report: header, one block per finding and a per-implementation summary. */
public final class TextReport {

    private TextReport() {
    }

    public static void write(AnalysisResult result, PrintWriter out) {
        ClassHierarchy h = result.hierarchy();
        out.println("=== Metot Akisi Analizi ===");
        out.println("Giris metot(lar)i : " + result.entries().stream()
                .map(MethodRef::toQualifiedString).collect(Collectors.joining(", ")));
        for (String target : result.targets()) {
            List<String> impls = h.allSubtypes(target).stream()
                    .filter(t -> !t.equals(target))
                    .filter(t -> h.get(t) != null && h.get(t).isConcrete())
                    .map(MethodRef::simpleName)
                    .toList();
            out.println("Hedef tip         : " + MethodRef.toDottedName(target)
                    + (impls.isEmpty() ? "" : "  (somut alt tipler: " + String.join(", ", impls) + ")"));
        }
        int maxDepth = result.depths().values().stream().mapToInt(Integer::intValue).max().orElse(0);
        out.println("Incelenen metot   : " + result.analysedMethods().size() + "  (en derin seviye: " + maxDepth + ")");
        out.println("Bulunan cagri     : " + result.findings().size());
        for (String w : result.warnings()) {
            out.println("UYARI: " + w);
        }

        int i = 1;
        for (Finding f : result.findings()) {
            out.println();
            out.println("[" + i++ + "] " + f.site().target().toShortString() + "  ->  " + targetsText(f));
            out.println("    Yer     : " + f.caller().toQualifiedString() + "  " + location(f.callerSourceFile(), f.site().line())
                    + "  [derinlik " + f.depth() + ", " + f.site().kind() + "]");
            List<String> notes = new ArrayList<>();
            if (result.targets().size() > 1) notes.add("hedef: " + MethodRef.simpleName(f.targetType()));
            if (f.site().viaLambda()) notes.add("lambda / metot referansi");
            if (f.interfaceMethod()) notes.add(MethodRef.simpleName(f.targetType()) + " metodu");
            if (!f.receiverTypeMatches()) notes.add("olasi: statik alici tipi "
                    + MethodRef.simpleName(f.site().target().owner()) + ", yalnizca dinamik baglamayla ulasiliyor");
            if (f.internalCall()) notes.add("uygulama sinifi icinden cagri");
            if (!notes.isEmpty()) {
                out.println("    Not     : " + String.join("; ", notes));
            }
            out.println("    Zincir  : " + chainText(f.chain(), h));
        }

        writeSummary(result, out);
        out.flush();
    }

    private static String targetsText(Finding f) {
        if (f.matchedTargets().isEmpty()) {
            return "(yuklenen siniflarda uygulamasi bulunamadi)";
        }
        return f.matchedTargets().stream()
                .map(t -> MethodRef.simpleName(t.receiverClass()) + (t.method().owner().equals(t.receiverClass())
                        ? "" : " (" + t.method().toShortString() + ")"))
                .collect(Collectors.joining(", "));
    }

    private static String chainText(List<ChainStep> chain, ClassHierarchy h) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chain.size(); i++) {
            ChainStep step = chain.get(i);
            if (i > 0) {
                sb.append("\n              ").append("   ".repeat(i - 1)).append("`- ");
                MethodRef prev = chain.get(i - 1).method();
                ClassInfo prevClass = h.get(prev.owner());
                sb.append(location(prevClass == null ? null : prevClass.sourceFile(), step.callLine())).append(' ');
            }
            if (step.via() != null) {
                sb.append('[').append(step.via()).append("] ");
            }
            sb.append(step.method().toShortString());
        }
        return sb.toString();
    }

    static String location(String sourceFile, int line) {
        String file = sourceFile == null ? "?" : sourceFile;
        return "(" + file + (line > 0 ? ":" + line : "") + ")";
    }

    private static void writeSummary(AnalysisResult result, PrintWriter out) {
        if (result.findings().isEmpty()) {
            return;
        }
        // implementation class -> method that runs -> number of call sites
        Map<String, Map<String, Integer>> byClass = new TreeMap<>();
        Map<String, TreeSet<String>> unresolved = new TreeMap<>();
        for (Finding f : result.findings()) {
            if (f.matchedTargets().isEmpty()) {
                unresolved.computeIfAbsent(f.site().target().ownerDotted(), k -> new TreeSet<>())
                        .add(f.site().target().toShortString());
            }
            for (DispatchTarget t : f.matchedTargets()) {
                byClass.computeIfAbsent(MethodRef.toDottedName(t.receiverClass()), k -> new TreeMap<>())
                        .merge(t.method().toShortString(), 1, Integer::sum);
            }
        }
        out.println();
        out.println("=== Uygulama sinifi bazinda ozet ===");
        byClass.forEach((cls, methods) -> {
            out.println(cls);
            methods.forEach((m, n) -> out.println("    " + m + "  <- " + n + " cagri yeri"));
        });
        unresolved.forEach((owner, methods) -> {
            out.println(owner + "  (uygulamasi bulunamadi)");
            methods.forEach(m -> out.println("    " + m));
        });
    }
}
