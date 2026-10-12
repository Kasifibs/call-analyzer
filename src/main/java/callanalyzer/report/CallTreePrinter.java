package callanalyzer.report;

import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallEdge;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prints the explored call graph as an indented tree. Each method is expanded only the first time it appears;
 * later occurrences are marked so that recursion and shared callees do not blow the output up. Calls that
 * neither reach a target type nor lead into analysed code (e.g. {@code System.out.println}) are omitted.
 *
 * <pre>
 * App#main(String[])
 *   -> Dealer#sell(ICar)  (App.java:9)
 *     * ICar#start()  (Dealer.java:7)
 *       => Bmw: AbstractCar#start()
 *         -> Engine#ignite()  (AbstractCar.java:12)
 * </pre>
 */
public final class CallTreePrinter {

    private final AnalysisResult result;
    private final PrintWriter out;
    private final Set<MethodRef> printed = new HashSet<>();
    private final Set<List<Object>> hits = new HashSet<>();
    private final Map<List<Object>, String> eventLabels = new HashMap<>();

    private CallTreePrinter(AnalysisResult result, PrintWriter out) {
        this.result = result;
        this.out = out;
        result.findings().forEach(f -> hits.add(List.of(f.caller(), f.site())));
        result.events().forEach(e -> result.edges().getOrDefault(e.caller(), List.of()).stream()
                .filter(edge -> edge.site().target().equals(e.dispatchMethod()) && edge.site().line() == e.line())
                .forEach(edge -> eventLabels.put(List.of(edge.caller(), edge.site()), e.label())));
    }

    public static void write(AnalysisResult result, PrintWriter out) {
        out.println();
        out.println("=== Cagri agaci (* = hedef tipe yapilan cagri, => = olasi uygulama, ^ = yukarida acildi) ===");
        CallTreePrinter printer = new CallTreePrinter(result, out);
        for (MethodRef entry : result.entries()) {
            out.println(entry.toShortString());
            printer.printChildren(entry, 1);
        }
        out.flush();
    }

    private void printChildren(MethodRef method, int indent) {
        if (!printed.add(method)) {
            return;
        }
        List<CallEdge> edges = result.edges().get(method);
        if (edges == null) {
            return;
        }
        ClassHierarchy h = result.hierarchy();
        ClassInfo cls = h.get(method.owner());
        String source = cls == null ? null : cls.sourceFile();
        for (CallEdge edge : edges) {
            boolean hit = isHit(edge);
            List<DispatchTarget> expandable = edge.targets().stream()
                    .filter(t -> result.edges().containsKey(t.method()))
                    .toList();
            String event = eventLabels.get(List.of(edge.caller(), edge.site()));
            if (!hit && event == null && expandable.isEmpty() && !hasUnexpandedAnalysable(edge)) {
                continue;
            }
            String marker = hit ? "* " : "-> ";
            String receivers = "";
            if (hit && edge.site().kind().isDynamicDispatch()) {
                receivers = "  {" + String.join(", ", edge.targets().stream()
                        .map(t -> MethodRef.simpleName(t.receiverClass())).distinct().toList()) + "}";
            }
            out.println(pad(indent) + marker + edge.site().target().toShortString() + "  "
                    + TextReport.location(source, edge.site().line())
                    + (edge.site().viaLambda() ? "  [lambda]" : "") + receivers
                    + (event == null ? "" : "  [" + event + (edge.targets().isEmpty() ? ", dinleyen yok" : "") + "]"));

            boolean single = edge.targets().size() == 1 && edge.targets().get(0).method().equals(edge.site().target());
            if (single) {
                printTarget(edge.targets().get(0).method(), indent + 1, null);
            } else {
                for (DispatchTarget t : edge.targets()) {
                    printTarget(t.method(), indent + 1, t.receiverClass());
                }
            }
        }
    }

    private void printTarget(MethodRef target, int indent, String receiver) {
        boolean analysed = result.edges().containsKey(target);
        if (receiver != null) {
            String label = target.owner().equals(receiver)
                    ? target.toShortString()
                    : MethodRef.simpleName(receiver) + ": " + target.toShortString();
            out.println(pad(indent) + "=> " + label + suffix(target, analysed));
            if (analysed) {
                printChildren(target, indent + 1);
            }
        } else if (printed.contains(target) && analysed) {
            out.println(pad(indent) + "^ " + target.toShortString());
        } else if (analysed) {
            printChildren(target, indent);
        } else if (result.hierarchy().method(target).isPresent()) {
            out.println(pad(indent) + "... (acilmadi: derinlik siniri ya da filtre)");
        }
    }

    private String suffix(MethodRef target, boolean analysed) {
        if (!analysed) {
            return result.hierarchy().method(target).isPresent() ? "  ... (acilmadi)" : "";
        }
        return printed.contains(target) ? "  ^" : "";
    }

    private boolean hasUnexpandedAnalysable(CallEdge edge) {
        return edge.targets().stream().anyMatch(t -> result.hierarchy().method(t.method())
                .map(MethodInfo::hasBody).orElse(false));
    }

    private boolean isHit(CallEdge edge) {
        return hits.contains(List.of(edge.caller(), edge.site()));
    }

    private static String pad(int indent) {
        return "  ".repeat(indent);
    }
}
