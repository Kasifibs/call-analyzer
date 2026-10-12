package callanalyzer.analysis;

import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.dataflow.BytecodeRepository;
import callanalyzer.dataflow.ValueResolver;
import callanalyzer.dataflow.ValueResolver.CallerLookup;
import callanalyzer.events.EventDispatch;
import callanalyzer.events.EventRouter;
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
 * <p>With an event configuration ({@link AnalysisOptions#events}), event dispatch calls continue only into the
 * observers registered for the published event (see {@link EventRouter}).
 *
 * <p>Every method is analysed once, so recursion and cycles terminate; thanks to the breadth-first order, the
 * chain stored for a finding is a shortest path from an entry method.
 */
public final class CallGraphAnalyzer {

    /** How a method was first reached; {@code via} describes an event hop, otherwise {@code null}. */
    private record Parent(MethodRef caller, CallSite site, String via) {
    }

    /** Upper bound on re-routing rounds, as a guard against oscillation. */
    private static final int MAX_EVENT_ROUNDS = 20;

    private final ClassHierarchy hierarchy;
    private final AnalysisOptions options;
    private final Map<String, byte[]> classBytes;
    private EventRouter sharedRouter;

    public CallGraphAnalyzer(ClassHierarchy hierarchy, AnalysisOptions options) {
        this(hierarchy, options, null);
    }

    /**
     * Reuses an already built event router (its registration table and parsed classes) instead of building one per
     * analysis; used when many entry methods are analysed in a row.
     */
    public CallGraphAnalyzer withEventRouter(EventRouter router) {
        this.sharedRouter = router;
        return this;
    }

    /** @param classBytes raw class files, needed for event routing ({@link AnalysisOptions#events}) */
    public CallGraphAnalyzer(ClassHierarchy hierarchy, AnalysisOptions options, Map<String, byte[]> classBytes) {
        this.hierarchy = hierarchy;
        this.options = options;
        this.classBytes = classBytes;
    }

    public AnalysisResult analyze() {
        if (options.entries().isEmpty()) {
            throw new IllegalArgumentException("En az bir giris metodu gerekli.");
        }
        if (options.targets().isEmpty()) {
            throw new IllegalArgumentException("En az bir hedef tip (or. com.example.ICar) gerekli.");
        }
        List<String> warnings = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (String t : options.targets()) {
            String internal = hierarchy.resolveClassName(t);
            if (!hierarchy.contains(internal)) {
                warnings.add("Hedef tip sinif yolunda yok, yalnizca adina gore eslestirilecek: " + t);
            } else if (hierarchy.allSubtypes(internal).size() == 1) {
                warnings.add("Hedef tipi uygulayan/genisleten yuklenmis bir sinif yok: " + t);
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

        EventRouter router = null;
        CallerLookup callers = null;
        if (options.events() != null) {
            if (sharedRouter != null) {
                router = sharedRouter;
            } else {
                if (classBytes == null) {
                    throw new IllegalArgumentException("Event takibi icin sinif dosyalari (classBytes) gerekli.");
                }
                router = new EventRouter(hierarchy, new BytecodeRepository(classBytes), options.events());
            }
            warnings.addAll(router.warnings());
            callers = ValueResolver.callersFromEdges(edges, router.repository());
        }

        List<Finding> found = new ArrayList<>();
        Map<List<Object>, EventDispatch> events = new LinkedHashMap<>(); // key: caller, index of the edge
        for (int round = 0; ; round++) {
            while (!queue.isEmpty()) {
                MethodRef current = queue.poll();
                int depth = depths.get(current);
                MethodInfo info = hierarchy.method(current).orElseThrow();
                List<CallEdge> out = new ArrayList<>();
                edges.put(current, out);
                for (CallSite site : info.calls()) {
                    List<DispatchTarget> dispatch;
                    String via = null;
                    if (router != null && router.isDispatchCall(site)) {
                        // Only the observers registered for the published event, not every observer.
                        EventDispatch ev = router.route(current, site, callers);
                        events.put(List.of(current, out.size()), ev);
                        dispatch = ev.observers();
                        via = ev.label();
                    } else {
                        dispatch = hierarchy.dispatch(site);
                    }
                    out.add(new CallEdge(current, site, dispatch));
                    for (String target : targets) {
                        match(current, depth, site, dispatch, target).ifPresent(found::add);
                    }
                    enqueue(current, site, dispatch, depth, via, depths, parents, queue);
                }
            }
            if (router == null || events.isEmpty() || round >= MAX_EVENT_ROUNDS) {
                break;
            }
            // Methods reached later may have revealed more callers (and so more events) for an earlier dispatch.
            boolean changed = false;
            for (Map.Entry<List<Object>, EventDispatch> e : events.entrySet()) {
                MethodRef caller = (MethodRef) e.getKey().get(0);
                int index = (Integer) e.getKey().get(1);
                CallEdge old = edges.get(caller).get(index);
                EventDispatch ev = router.route(caller, old.site(), callers);
                if (ev.observers().equals(old.targets())) {
                    e.setValue(ev);
                    continue;
                }
                changed = true;
                e.setValue(ev);
                edges.get(caller).set(index, new CallEdge(caller, old.site(), ev.observers()));
                found.removeIf(f -> f.caller().equals(caller) && f.site().equals(old.site()));
                for (String target : targets) {
                    match(caller, depths.get(caller), old.site(), ev.observers(), target).ifPresent(found::add);
                }
                enqueue(caller, old.site(), ev.observers(), depths.get(caller), ev.label(), depths, parents, queue);
            }
            if (!changed) {
                break;
            }
        }

        // Chains are built once the traversal is done, so that they follow the final (shortest) parent links.
        List<Finding> findings = found.stream()
                .map(f -> new Finding(f.targetType(), f.caller(), f.callerSourceFile(), f.site(), f.depth(),
                        f.matchedTargets(), f.receiverTypeMatches(), f.interfaceMethod(), f.internalCall(),
                        chainTo(f.caller(), parents)))
                .toList();
        Map<MethodRef, List<CallEdge>> frozenEdges = new LinkedHashMap<>();
        edges.forEach((k, v) -> frozenEdges.put(k, List.copyOf(v)));
        return new AnalysisResult(List.copyOf(entries), List.copyOf(targets), findings,
                Collections.unmodifiableMap(frozenEdges), Collections.unmodifiableMap(depths), hierarchy,
                List.copyOf(warnings), List.copyOf(events.values()),
                router == null ? List.of() : router.registrations());
    }

    private void enqueue(MethodRef caller, CallSite site, List<DispatchTarget> dispatch, int depth, String via,
                         Map<MethodRef, Integer> depths, Map<MethodRef, Parent> parents, Deque<MethodRef> queue) {
        if (options.maxDepth() >= 0 && depth + 1 > options.maxDepth()) {
            return;
        }
        for (DispatchTarget t : dispatch) {
            MethodRef callee = t.method();
            if (!depths.containsKey(callee) && isExpandable(callee)) {
                depths.put(callee, depth + 1);
                parents.put(callee, new Parent(caller, site, via));
                queue.add(callee);
            }
        }
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
            chain.add(new ChainStep(current, p.site().line(), p.via()));
            current = p.caller();
            p = parents.get(current);
        }
        chain.add(new ChainStep(current, -1, null));
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
        return options.isIncluded(owner);
    }
}
