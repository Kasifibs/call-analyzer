package callanalyzer.scan;

import callanalyzer.acquire.AcquireAnalyzer;
import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.AcquireExemption;
import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallGraphAnalyzer;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.MethodSpec;
import callanalyzer.dataflow.BytecodeRepository;
import callanalyzer.events.EventRouter;
import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Finds every method that acquires cars itself (calls the acquirer method or carries the acquire annotation) and
 * analyses each of them as an entry method, with the same options otherwise.
 */
public final class AcquireScanner {

    private final ClassHierarchy hierarchy;
    private final Map<String, byte[]> classBytes;
    private final AnalysisOptions options;
    private final String acquirer;
    private final String annotation;
    private final MethodSpec acquirerSpec;
    private final String acquirerClass;
    private final String annotationDesc;
    private List<AcquireExemption> exemptions = List.of();
    private String releaser;

    /**
     * @param options    analysis options; their entries are ignored, the include/exclude filters also limit which
     *                   classes are scanned for candidates
     * @param acquirer   acquirer method, e.g. {@code com.example.CarAcquirer#acquire}; may be {@code null}
     * @param annotation acquire annotation, e.g. {@code com.example.AcquireCar}; may be {@code null}
     */
    public AcquireScanner(ClassHierarchy hierarchy, Map<String, byte[]> classBytes, AnalysisOptions options,
                          String acquirer, String annotation) {
        if (acquirer == null && annotation == null) {
            throw new IllegalArgumentException("--scan icin --acquirer ve/veya --acquire-annotation gerekli.");
        }
        this.hierarchy = hierarchy;
        this.classBytes = classBytes;
        this.options = options;
        this.acquirer = acquirer;
        this.annotation = annotation;
        this.acquirerSpec = acquirer == null ? null : MethodSpec.parse(acquirer);
        if (acquirerSpec != null && acquirerSpec.methodName() == null) {
            throw new IllegalArgumentException("--acquirer bir metot belirtmeli, or. com.example.CarAcquirer#acquire");
        }
        this.acquirerClass = acquirerSpec == null ? null : hierarchy.resolveClassName(acquirerSpec.className());
        this.annotationDesc = annotation == null ? null : "L" + hierarchy.resolveClassName(annotation) + ";";
    }

    /** Classes given methods may use without acquiring them; see {@link AcquireExemption}. */
    public AcquireScanner withExemptions(List<AcquireExemption> exemptions) {
        this.exemptions = List.copyOf(exemptions);
        return this;
    }

    /** Release method to check the acquire calls against; see {@link AcquireAnalyzer#withReleaser}. */
    public AcquireScanner withReleaser(String releaser) {
        this.releaser = releaser;
        return this;
    }

    /** Methods calling the acquirer or carrying the annotation, sorted by class and method. */
    public List<Candidate> candidates() {
        Map<MethodRef, boolean[]> found = new LinkedHashMap<>();
        for (String cls : new TreeSet<>(hierarchy.classes().keySet())) {
            if (!options.isIncluded(cls) || cls.equals(acquirerClass)) {
                continue;
            }
            ClassInfo info = hierarchy.get(cls);
            List<MethodInfo> methods = new ArrayList<>(info.allMethods());
            methods.sort((a, b) -> a.ref().compareTo(b.ref()));
            for (MethodInfo m : methods) {
                boolean annotated = annotationDesc != null && m.annotations().contains(annotationDesc);
                boolean calls = acquirerSpec != null && callsAcquirer(m);
                if (!annotated && !calls) {
                    continue;
                }
                // An acquire call inside a lambda belongs to the method that defines the lambda.
                MethodRef owner = m.isSynthetic() ? enclosing(m.ref()) : m.ref();
                boolean[] flags = found.computeIfAbsent(owner, k -> new boolean[2]);
                flags[0] |= calls;
                flags[1] |= annotated;
            }
        }
        List<Candidate> result = new ArrayList<>();
        found.forEach((m, f) -> result.add(new Candidate(m, f[0], f[1])));
        return result;
    }

    private boolean callsAcquirer(MethodInfo m) {
        for (CallSite site : m.calls()) {
            if (AcquireAnalyzer.isAcquirerCall(hierarchy, acquirerSpec, acquirerClass, site, hierarchy.dispatch(site))) {
                return true;
            }
        }
        return false;
    }

    /** The non-synthetic method whose (possibly nested) lambda {@code lambda} is; itself if none is found. */
    private MethodRef enclosing(MethodRef lambda) {
        MethodRef current = lambda;
        for (int guard = 0; guard < 10; guard++) {
            ClassInfo cls = hierarchy.get(current.owner());
            MethodRef target = current;
            MethodInfo definer = cls.allMethods().stream()
                    .filter(mi -> mi.calls().stream().anyMatch(cs -> cs.viaLambda() && cs.target().equals(target)))
                    .findFirst().orElse(null);
            if (definer == null) {
                return current;
            }
            if (!definer.isSynthetic()) {
                return definer.ref();
            }
            current = definer.ref();
        }
        return current;
    }

    /**
     * Analyses every candidate. {@code onEach} receives each item together with its full analysis result (null on
     * error) as soon as it is ready, so callers can write per-method reports without keeping every call graph.
     */
    public ScanResult run(List<Candidate> candidates, Consumer<String> progress,
                          BiConsumer<ScanItem, AnalysisResult> onEach) {
        BytecodeRepository repo = new BytecodeRepository(classBytes);
        EventRouter router = options.events() == null ? null : new EventRouter(hierarchy, repo, options.events());
        List<ScanItem> items = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            MethodRef m = c.method();
            progress.accept("[" + (i + 1) + "/" + candidates.size() + "] " + m.toQualifiedString());
            ScanItem item;
            AnalysisResult result = null;
            try {
                String spec = m.ownerDotted() + "#" + m.name() + m.desc();
                result = new CallGraphAnalyzer(hierarchy, options.withEntry(spec), classBytes)
                        .withEventRouter(router).analyze();
                AcquireComparison cmp = new AcquireAnalyzer(result, repo, acquirer, annotation)
                        .withExemptions(exemptions).withReleaser(releaser).analyze();
                item = new ScanItem(i + 1, c, result.findings().size(), result.analysedMethods().size(), cmp, null);
            } catch (RuntimeException e) {
                item = new ScanItem(i + 1, c, 0, 0, null, e.toString());
                result = null;
            }
            items.add(item);
            onEach.accept(item, result);
        }
        return new ScanResult(acquirer, annotationDesc == null ? null
                : annotationDesc.substring(1, annotationDesc.length() - 1), List.copyOf(items));
    }
}
