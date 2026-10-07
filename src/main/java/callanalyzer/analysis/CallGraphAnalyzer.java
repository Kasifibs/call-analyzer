package callanalyzer.analysis;

import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.InvokeKind;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Walks the call graph breadth-first from the entry methods, descending into every resolvable callee
 * (including the implementations of interface/virtual calls, lambdas and method references), and records each
 * call that may reach a class implementing one of the target types.
 *
 * <p>Every method is analysed once, so recursion and cycles terminate; thanks to the breadth-first order, the
 * chain stored for a finding is a shortest path from an entry method.
 */
public final class CallGraphAnalyzer {

    private record Parent(MethodRef caller, CallSite site) {
    }

    private final ClassHierarchy hierarchy;
    private final AnalysisOptions options;

    public CallGraphAnalyzer(ClassHierarchy hierarchy, AnalysisOptions options) {
        this.hierarchy = hierarchy;
        this.options = options;
    }

    public AnalysisResult analyze() {
        if (options.entries().isEmpty()) {
            throw new IllegalArgumentException("En az bir giriş metodu gerekli.");
        }
        if (options.targets().isEmpty()) {
            throw new IllegalArgumentException("En az bir hedef tip (ör. com.example.ICar) gerekli.");
        }
        List<String> warnings = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (String t : options.targets()) {
            String internal = hierarchy.resolveClassName(t);
            if (!hierarchy.contains(internal)) {
                warnings.add("Hedef tip sınıf yolunda yok, yalnızca adına göre eşleştirilecek: " + t);
            } else if (hierarchy.allSubtypes(internal).size() == 1) {
                warnings.add("Hedef tipi uygulayan/genişleten yüklenmiş bir sınıf yok: " + t);
            }
            targets.add(internal);
        }

        Map<MethodRef, Integer> depths = new LinkedHashMap<>();
        Map<MethodRef, Parent> parents = new HashMap<>();
        Map<MethodRef, List<CallEdge>> edges = new LinkedHashMap<>();
        Deque<MethodRef> queue = new ArrayDeque<>();

        Set<MethodRef> entries = new LinkedHashSet<>();
        for (String spec : options.entries()) {
            for (MethodRef ref : MethodSpec.parse(spec).resolve(hierarchy)) {
                entries.addAll(entryBodies(ref));
            }
        }
        for (MethodRef e : entries) {
            depths.put(e, 0);
            queue.add(e);
        }

        List<Finding> found = new ArrayList<>();
        while (!queue.isEmpty()) {
            MethodRef current = queue.poll();
            int depth = depths.get(current);
            MethodInfo info = hierarchy.method(current).orElseThrow();
            List<CallEdge> out = new ArrayList<>();
            for (CallSite site : info.calls()) {
                List<DispatchTarget> dispatch = hierarchy.dispatch(site);
                out.add(new CallEdge(current, site, dispatch));
                for (String target : targets) {
                    match(current, depth, site, dispatch, target).ifPresent(found::add);
                }
                if (options.maxDepth() >= 0 && depth + 1 > options.maxDepth()) {
                    continue;
                }
                for (DispatchTarget t : dispatch) {
                    MethodRef callee = t.method();
                    if (!depths.containsKey(callee) && isExpandable(callee)) {
                        depths.put(callee, depth + 1);
                        parents.put(callee, new Parent(current, site));
                        queue.add(callee);
                    }
                }
            }
            edges.put(current, Collections.unmodifiableList(out));
        }

        // Chains are built once the traversal is done, so that they follow the final (shortest) parent links.
        List<Finding> findings = found.stream()
                .map(f -> new Finding(f.targetType(), f.caller(), f.callerSourceFile(), f.site(), f.depth(),
                        f.matchedTargets(), f.receiverTypeMatches(), f.interfaceMethod(), f.internalCall(),
                        chainTo(f.caller(), parents)))
                .toList();
        return new AnalysisResult(List.copyOf(entries), List.copyOf(targets), findings,
                Collections.unmodifiableMap(edges), Collections.unmodifiableMap(depths), hierarchy,
                List.copyOf(warnings));
    }

    /**
     * An entry with a body is analysed directly; an abstract entry (e.g. {@code ICar#start}) stands for all of
     * its implementations.
     */
    private List<MethodRef> entryBodies(MethodRef ref) {
        MethodInfo m = hierarchy.method(ref).orElseThrow();
        if (m.hasBody()) {
            return List.of(ref);
        }
        CallSite synthetic = new CallSite(InvokeKind.INTERFACE, ref, -1, false);
        return hierarchy.dispatch(synthetic).stream()
                .map(DispatchTarget::method)
                .filter(this::isExpandableIgnoringFilters)
                .distinct()
                .toList();
    }

    private Optional<Finding> match(MethodRef caller, int depth, CallSite site, List<DispatchTarget> dispatch, String target) {
        MethodRef called = site.target();
        if (called.isConstructor() && !options.includeConstructors()) {
            return Optional.empty();
        }
        boolean receiverMatches = hierarchy.isSubtypeOf(called.owner(), target);
        List<DispatchTarget> matched = dispatch.stream()
                .filter(t -> hierarchy.isSubtypeOf(t.receiverClass(), target))
                .toList();
        if (!receiverMatches && (matched.isEmpty() || options.directOnly())) {
            return Optional.empty();
        }
        boolean interfaceMethod = !called.isConstructor()
                && hierarchy.resolveDeclared(new MethodRef(target, called.name(), called.desc())).isPresent();
        if (options.onlyInterfaceMethods() && !interfaceMethod) {
            return Optional.empty();
        }
        boolean internal = hierarchy.isSubtypeOf(caller.owner(), target);
        if (options.skipInternalCalls() && internal) {
            return Optional.empty();
        }
        ClassInfo callerClass = hierarchy.get(caller.owner());
        return Optional.of(new Finding(target, caller, callerClass == null ? null : callerClass.sourceFile(), site,
                depth, matched, receiverMatches, interfaceMethod, internal, List.of()));
    }

    private List<ChainStep> chainTo(MethodRef method, Map<MethodRef, Parent> parents) {
        List<ChainStep> chain = new ArrayList<>();
        MethodRef current = method;
        Parent p = parents.get(current);
        while (p != null) {
            chain.add(new ChainStep(current, p.site().line()));
            current = p.caller();
            p = parents.get(current);
        }
        chain.add(new ChainStep(current, -1));
        Collections.reverse(chain);
        return List.copyOf(chain);
    }

    private boolean isExpandable(MethodRef ref) {
        return isExpandableIgnoringFilters(ref) && isIncluded(ref.owner());
    }

    private boolean isExpandableIgnoringFilters(MethodRef ref) {
        return hierarchy.method(ref).map(MethodInfo::hasBody).orElse(false);
    }

    private boolean isIncluded(String owner) {
        for (String ex : options.excludePrefixes()) {
            if (owner.startsWith(ex)) {
                return false;
            }
        }
        if (options.includePrefixes().isEmpty()) {
            return true;
        }
        for (String in : options.includePrefixes()) {
            if (owner.startsWith(in)) {
                return true;
            }
        }
        return false;
    }
}
