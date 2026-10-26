package callanalyzer.acquire;

import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallEdge;
import callanalyzer.analysis.ChainStep;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.analysis.Finding;
import callanalyzer.analysis.MethodSpec;
import callanalyzer.dataflow.BytecodeRepository;
import callanalyzer.dataflow.FinallyBlocks;
import callanalyzer.dataflow.MethodDataFlow;
import callanalyzer.dataflow.TrackedValue;
import callanalyzer.dataflow.ValueResolver;
import callanalyzer.dataflow.ValueResolver.Resolved;
import callanalyzer.dataflow.ValueSource;
import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.InvokeKind;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finds the calls of an "acquirer" method (e.g. {@code CarAcquirer.acquire(List<ICar>)}) in the entry method(s) and
 * determines which target implementations are put into the list it receives; and/or reads the acquire annotation
 * on the entry methods (e.g. {@code @AcquireCar(cars = {Opel.class, Mercedes.class})}). The acquired classes are
 * then compared with the implementations the flow actually calls.
 *
 * <p>The list contents are recovered with {@link ValueResolver}, so fields typed with a concrete class
 * ({@code @Autowired Bmw bmw}), {@code new X()}, {@code List.of}, {@code add} calls, getters, list fields and
 * parameters (through the callers found in the analysed flow) are all understood.
 */
public final class AcquireAnalyzer {

    private final ClassHierarchy hierarchy;
    private final BytecodeRepository repo;
    private final AnalysisResult result;
    private final MethodSpec acquirerSpec;
    private final String acquirerClass;
    private final String annotationType;

    private List<AcquireExemption> exemptions = List.of();
    private MethodSpec releaserSpec;
    private String releaserClass;
    private ValueResolver resolver;

    public AcquireAnalyzer(AnalysisResult result, Map<String, byte[]> classBytes, String acquirerSpec) {
        this(result, classBytes, acquirerSpec, null);
    }

    /**
     * @param acquirerSpec   acquirer method, e.g. {@code com.example.CarAcquirer#acquire}; may be {@code null}
     * @param annotationType acquire annotation, e.g. {@code com.example.AcquireCar}; may be {@code null}
     */
    public AcquireAnalyzer(AnalysisResult result, Map<String, byte[]> classBytes, String acquirerSpec,
                           String annotationType) {
        this(result, new BytecodeRepository(classBytes), acquirerSpec, annotationType);
    }

    /** Same, sharing already parsed classes (used when many entry methods are analysed in a row). */
    public AcquireAnalyzer(AnalysisResult result, BytecodeRepository repo, String acquirerSpec,
                           String annotationType) {
        if (acquirerSpec == null && annotationType == null) {
            throw new IllegalArgumentException("Acquire metodu ya da anotasyonu verilmeli.");
        }
        this.result = result;
        this.hierarchy = result.hierarchy();
        this.repo = repo;
        if (acquirerSpec != null) {
            this.acquirerSpec = MethodSpec.parse(acquirerSpec);
            if (this.acquirerSpec.methodName() == null) {
                throw new IllegalArgumentException(
                        "--acquirer bir metot belirtmeli, or. com.example.CarAcquirer#acquire");
            }
            this.acquirerClass = hierarchy.resolveClassName(this.acquirerSpec.className());
        } else {
            this.acquirerSpec = null;
            this.acquirerClass = null;
        }
        this.annotationType = annotationType == null ? null : hierarchy.resolveClassName(annotationType);
    }

    /** Classes the entry methods may use without acquiring them; see {@link AcquireExemption}. */
    public AcquireAnalyzer withExemptions(List<AcquireExemption> exemptions) {
        this.exemptions = List.copyOf(exemptions);
        return this;
    }

    /**
     * Also checks that every acquire call of the entry method is released in a {@code finally} block of the same
     * method by calling {@code releaserSpec} (e.g. {@code com.example.CarAcquirer#release}) with the same elements.
     */
    public AcquireAnalyzer withReleaser(String releaserSpec) {
        if (releaserSpec == null) {
            this.releaserSpec = null;
            this.releaserClass = null;
            return this;
        }
        if (acquirerSpec == null) {
            throw new IllegalArgumentException("Release kontrolu icin acquire metodu (--acquirer) da verilmeli.");
        }
        this.releaserSpec = MethodSpec.parse(releaserSpec);
        if (this.releaserSpec.methodName() == null) {
            throw new IllegalArgumentException("--releaser bir metot belirtmeli, or. com.example.LockAcquirer#release");
        }
        this.releaserClass = hierarchy.resolveClassName(this.releaserSpec.className());
        return this;
    }

    public AcquireComparison analyze() {
        Set<MethodRef> acquirerMethods = new HashSet<>();
        List<AcquireCall> calls = new ArrayList<>();
        for (Map.Entry<MethodRef, List<CallEdge>> e : result.edges().entrySet()) {
            boolean callsAcquirer = false;
            for (CallEdge edge : e.getValue()) {
                if (isAcquirerCall(edge.site(), edge.targets())) {
                    callsAcquirer = true;
                    acquirerMethods.add(edge.site().target());
                    edge.targets().forEach(t -> acquirerMethods.add(t.method()));
                } else if (isReleaserCall(edge.site(), edge.targets())) {
                    // what the releaser does with the elements is no usage either
                    acquirerMethods.add(edge.site().target());
                    edge.targets().forEach(t -> acquirerMethods.add(t.method()));
                }
            }
            if (callsAcquirer) {
                repo.flow(e.getKey()).ifPresent(flow -> calls.addAll(acquireCallsIn(flow)));
            }
        }

        // Like the annotation, only the entry method's own acquire calls count: an acquire made somewhere deeper in the
        // flow (a helper, an event observer, ...) must not hide classes the entry method itself failed to acquire.
        Set<MethodRef> entries = ownBodies(hierarchy, result.entries());
        List<AcquireCall> otherCalls = calls.stream().filter(c -> !entries.contains(c.caller())).toList();
        calls.removeIf(c -> !entries.contains(c.caller()));

        List<AnnotationCheck> annotations = annotationType == null ? List.of() : annotationChecks();

        Set<String> acquired = new TreeSet<>();
        List<AcquiredCar> unresolved = new ArrayList<>();
        List<AcquiredCar> allCars = new ArrayList<>();
        calls.forEach(c -> allCars.addAll(c.cars()));
        annotations.forEach(a -> allCars.addAll(a.cars()));
        for (AcquiredCar car : allCars) {
            if (car.isResolved()) {
                acquired.add(car.concreteClass());
            } else if (!unresolved.contains(car)) {
                unresolved.add(car);
            }
        }
        return compare(calls, otherCalls, annotations, acquired, unresolved, acquirerMethods,
                releaserSpec == null ? null : releaseCheck(calls));
    }

    // ------------------------------------------------------------------------------------------------ comparison

    private AcquireComparison compare(List<AcquireCall> calls, List<AcquireCall> otherCalls,
                                      List<AnnotationCheck> annotations, Set<String> acquired,
                                      List<AcquiredCar> unresolved, Set<MethodRef> acquirerMethods,
                                      ReleaseCheck release) {
        Map<String, List<Finding>> certain = new TreeMap<>();
        Map<String, List<Finding>> possible = new TreeMap<>();
        for (Finding f : result.findings()) {
            Set<String> receivers = usedReceivers(f, acquirerMethods);
            for (String r : receivers) {
                (receivers.size() == 1 ? certain : possible).computeIfAbsent(r, k -> new ArrayList<>()).add(f);
            }
        }
        Set<String> used = new TreeSet<>(certain.keySet());
        used.addAll(possible.keySet());

        List<ClassUsage> covered = new ArrayList<>();
        List<ClassUsage> missing = new ArrayList<>();
        List<ClassUsage> possiblyMissing = new ArrayList<>();
        List<ClassUsage> undecided = new ArrayList<>();
        List<ClassUsage> exempt = new ArrayList<>();
        List<String> exemptClasses = AcquireExemption.classesFor(hierarchy, exemptions, result.entries());
        for (String cls : used) {
            ClassUsage usage = new ClassUsage(cls, certain.getOrDefault(cls, List.of()),
                    possible.getOrDefault(cls, List.of()));
            if (acquired.stream().anyMatch(a -> hierarchy.isSubtypeOf(cls, a))) {
                covered.add(usage);
            } else if (exemptClasses.stream().anyMatch(e -> hierarchy.isSubtypeOf(cls, e))) {
                exempt.add(usage);
            } else if (unresolved.stream().anyMatch(u -> hierarchy.isSubtypeOf(cls, u.declaredType()))) {
                undecided.add(usage);
            } else if (!usage.certain().isEmpty()) {
                missing.add(usage);
            } else {
                possiblyMissing.add(usage);
            }
        }
        List<String> unused = acquired.stream()
                .filter(a -> used.stream().noneMatch(u -> hierarchy.isSubtypeOf(u, a)))
                .toList();
        return new AcquireComparison(acquirerSpec == null ? null : acquirerSpec.text(), annotationType,
                List.copyOf(calls), List.copyOf(otherCalls), List.copyOf(annotations), List.copyOf(acquired),
                List.copyOf(unresolved), covered, missing, possiblyMissing, undecided, exempt, unused, release);
    }

    /**
     * The concrete target implementations a finding needs an instance of. Ignored: static calls and constructors
     * (no acquired instance involved), calls made inside the acquirer, and calls an implementation makes on its own
     * methods (on {@code this}, so on an instance that is already accounted for).
     */
    private Set<String> usedReceivers(Finding f, Set<MethodRef> acquirerMethods) {
        if (f.site().kind() == InvokeKind.STATIC || f.site().target().isConstructor()) {
            return Set.of();
        }
        for (ChainStep step : f.chain()) {
            if (acquirerMethods.contains(step.method())) {
                return Set.of();
            }
        }
        Set<String> receivers = new TreeSet<>();
        for (DispatchTarget t : f.matchedTargets()) {
            ClassInfo c = hierarchy.get(t.receiverClass());
            if (c != null && c.isConcrete()) {
                receivers.add(t.receiverClass());
            }
        }
        if (f.internalCall() && receivers.stream().allMatch(r -> hierarchy.isSubtypeOf(r, f.caller().owner()))) {
            return Set.of();
        }
        return receivers;
    }

    // ----------------------------------------------------------------------------------------- finding acquirers

    private boolean isReleaserCall(CallSite site, List<DispatchTarget> targets) {
        return releaserSpec != null && isAcquirerCall(hierarchy, releaserSpec, releaserClass, site, targets);
    }

    private boolean isAcquirerCall(CallSite site, List<DispatchTarget> targets) {
        return acquirerSpec != null && isAcquirerCall(hierarchy, acquirerSpec, acquirerClass, site, targets);
    }

    /** Whether {@code site} calls the acquirer method ({@code acquirerClass} is its resolved internal name). */
    public static boolean isAcquirerCall(ClassHierarchy hierarchy, MethodSpec acquirer, String acquirerClass,
                                         CallSite site, List<DispatchTarget> targets) {
        if (!acquirer.matchesSignature(site.target())) {
            return false;
        }
        return hierarchy.isSubtypeOf(site.target().owner(), acquirerClass)
                || targets.stream().anyMatch(t -> hierarchy.isSubtypeOf(t.method().owner(), acquirerClass));
    }

    /**
     * The entry methods plus the lambda bodies defined in them (also nested ones): code in
     * {@code template.execute(() -> CarAcquirer.acquire(...))} belongs to the entry method.
     */
    public static Set<MethodRef> ownBodies(ClassHierarchy hierarchy, java.util.Collection<MethodRef> entries) {
        Set<MethodRef> own = new HashSet<>();
        java.util.Deque<MethodRef> work = new java.util.ArrayDeque<>(entries);
        while (!work.isEmpty()) {
            MethodRef m = work.poll();
            if (!own.add(m)) {
                continue;
            }
            hierarchy.method(m).ifPresent(info -> info.calls().stream()
                    .filter(CallSite::viaLambda)
                    .map(CallSite::target)
                    .filter(t -> t.owner().equals(m.owner()) && t.name().startsWith("lambda$"))
                    .forEach(work::add));
        }
        return own;
    }

    private List<AcquireCall> acquireCallsIn(MethodDataFlow flow) {
        List<AcquireCall> calls = new ArrayList<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (!(insn instanceof MethodInsnNode m)) {
                continue;
            }
            CallSite site = siteOf(m);
            if (!isAcquirerCall(site, hierarchy.dispatch(site))) {
                continue;
            }
            calls.add(new AcquireCall(flow.method(), flow.sourceFile(), flow.lineOf(insn), site.target(),
                    List.copyOf(carsPassedTo(flow, m))));
        }
        return calls;
    }

    private static CallSite siteOf(MethodInsnNode m) {
        return new CallSite(BytecodeRepository.kindOf(m), new MethodRef(m.owner, m.name, m.desc), -1, false);
    }

    /** Target type elements of the lists (or single values) passed to {@code call}. */
    private Set<AcquiredCar> carsPassedTo(MethodDataFlow flow, MethodInsnNode call) {
        Set<AcquiredCar> cars = new LinkedHashSet<>();
        for (TrackedValue arg : flow.arguments(call)) {
            Set<AcquiredCar> found = resolveCars(flow, arg);
            if (found.isEmpty() && arg.sources().stream().anyMatch(src -> src instanceof ValueSource.Parameter)) {
                // acquire(cars) where the list is a parameter nobody in the flow passes: contents unknown
                found = Set.of(new AcquiredCar(null, result.targets().get(0), flow.method().toShortString()
                        + " metoduna disaridan gelen liste (icerigi bilinmiyor)"));
            }
            cars.addAll(found);
        }
        return cars;
    }

    // ------------------------------------------------------------------------------------------------- release

    /**
     * For every element of every acquire call: is it released after the acquire, in a finally block, by a release
     * call of the same method? Release calls elsewhere (a helper method, a caller) do not count.
     */
    private ReleaseCheck releaseCheck(List<AcquireCall> calls) {
        List<ReleaseCall> releaseCalls = new ArrayList<>();
        List<AcquireRelease> elements = new ArrayList<>();
        Set<MethodRef> callers = new LinkedHashSet<>();
        calls.forEach(c -> callers.add(c.caller()));
        for (MethodRef caller : callers) {
            MethodDataFlow flow = repo.flow(caller).orElse(null);
            if (flow == null) {
                continue;
            }
            List<MethodInsnNode> acquires = new ArrayList<>();
            Map<MethodInsnNode, Set<AcquiredCar>> releases = new java.util.LinkedHashMap<>();
            for (AbstractInsnNode insn : flow.instructions()) {
                if (insn instanceof MethodInsnNode m) {
                    CallSite site = siteOf(m);
                    if (isAcquirerCall(site, hierarchy.dispatch(site))) {
                        acquires.add(m);
                    } else if (isReleaserCall(site, hierarchy.dispatch(site))) {
                        releases.put(m, carsPassedTo(flow, m));
                    }
                }
            }
            // javac copies finally blocks, so one source line can hold several release instructions
            Set<AbstractInsnNode> allFinally = acquires.isEmpty() ? Set.of()
                    : FinallyBlocks.protecting(flow, flow.instructions().getFirst());
            Map<Integer, ReleaseCall> byLine = new TreeMap<>();
            releases.forEach((m, cars) -> byLine.merge(flow.lineOf(m),
                    new ReleaseCall(caller, flow.sourceFile(), flow.lineOf(m), allFinally.contains(m), List.copyOf(cars)),
                    (a, b) -> a.inFinally() ? a : b));
            releaseCalls.addAll(byLine.values());

            Set<String> seen = new HashSet<>();
            for (MethodInsnNode acquire : acquires) {
                int line = flow.lineOf(acquire);
                Set<AbstractInsnNode> protecting = FinallyBlocks.protecting(flow, acquire);
                int at = flow.indexOf(acquire);
                for (AcquiredCar car : carsPassedTo(flow, acquire)) {
                    if (!seen.add(line + " " + car)) {
                        continue; // the same acquire copied into several paths
                    }
                    AcquireRelease.Status status = AcquireRelease.Status.UNRELEASED;
                    MethodInsnNode by = null;
                    for (Map.Entry<MethodInsnNode, Set<AcquiredCar>> r : releases.entrySet()) {
                        AcquireRelease.Status s;
                        if (r.getValue().stream().anyMatch(rc -> releases(rc, car))) {
                            s = protecting.contains(r.getKey()) ? AcquireRelease.Status.RELEASED
                                    : flow.indexOf(r.getKey()) > at ? AcquireRelease.Status.NOT_IN_FINALLY : null;
                        } else if (r.getValue().stream().anyMatch(rc -> mayRelease(rc, car))
                                && (protecting.contains(r.getKey()) || flow.indexOf(r.getKey()) > at)) {
                            s = AcquireRelease.Status.UNDECIDED;
                        } else {
                            s = null;
                        }
                        if (s != null && s.compareTo(status) < 0) {
                            status = s;
                            by = r.getKey();
                        }
                    }
                    ReleaseCall release = by == null ? null : byLine.get(flow.lineOf(by));
                    elements.add(new AcquireRelease(caller, flow.sourceFile(), line, car, status, release));
                }
            }
        }
        return new ReleaseCheck(releaserSpec.text(), List.copyOf(releaseCalls), List.copyOf(elements));
    }

    /** Whether releasing {@code released} certainly releases the acquired element. */
    private boolean releases(AcquiredCar released, AcquiredCar acquired) {
        if (acquired.isResolved() && released.isResolved()) {
            return hierarchy.isSubtypeOf(acquired.concreteClass(), released.concreteClass());
        }
        // same unknown value, e.g. acquire(cars) ... release(cars) with cars a parameter
        return !acquired.isResolved() && released.equals(acquired);
    }

    /** Whether one of the two is unknown and could be the other. */
    private boolean mayRelease(AcquiredCar released, AcquiredCar acquired) {
        if (!released.isResolved()) {
            return hierarchy.isSubtypeOf(acquired.isResolved() ? acquired.concreteClass() : acquired.declaredType(),
                    released.declaredType())
                    || hierarchy.isSubtypeOf(released.declaredType(), acquired.declaredType());
        }
        return !acquired.isResolved() && hierarchy.isSubtypeOf(released.concreteClass(), acquired.declaredType());
    }

    // --------------------------------------------------------------------------------------------- annotation

    /** Checks every entry method for the acquire annotation and collects the classes listed in it. */
    private List<AnnotationCheck> annotationChecks() {
        String desc = "L" + annotationType + ";";
        String shortName = "@" + simple(annotationType);
        List<AnnotationCheck> checks = new ArrayList<>();
        for (MethodRef entry : result.entries()) {
            ClassNode owner = repo.classNode(entry.owner()).orElse(null);
            MethodNode method = owner == null ? null : owner.methods.stream()
                    .filter(m -> m.name.equals(entry.name()) && m.desc.equals(entry.desc()))
                    .findFirst().orElse(null);
            if (method == null) {
                continue;
            }
            List<AnnotationNode> all = new ArrayList<>();
            if (method.visibleAnnotations != null) all.addAll(method.visibleAnnotations);
            if (method.invisibleAnnotations != null) all.addAll(method.invisibleAnnotations);
            List<AnnotationNode> matching = all.stream().filter(a -> a.desc.equals(desc)).toList();
            Set<AcquiredCar> cars = new LinkedHashSet<>();
            for (AnnotationNode a : matching) {
                List<Type> classes = new ArrayList<>();
                collectClassValues(a.values, classes);
                for (Type t : classes) {
                    String cls = t.getInternalName();
                    String concrete = hierarchy.contains(cls) ? concreteOf(cls) : cls;
                    String origin = shortName + " ile verildi: " + simple(cls) + ".class"
                            + (hierarchy.contains(cls) && !isTargetType(cls) ? "  (hedef tipi uygulamiyor!)" : "");
                    cars.add(new AcquiredCar(concrete, cls, origin));
                }
            }
            checks.add(new AnnotationCheck(entry, owner.sourceFile, firstLine(method), !matching.isEmpty(),
                    List.copyOf(cars)));
        }
        return checks;
    }

    /** Class literals in annotation values, also inside arrays and nested annotations. */
    private static void collectClassValues(Object values, List<Type> out) {
        if (values instanceof Type t && t.getSort() == Type.OBJECT) {
            out.add(t);
        } else if (values instanceof List<?> list) {
            list.forEach(v -> collectClassValues(v, out));
        } else if (values instanceof AnnotationNode nested) {
            collectClassValues(nested.values, out);
        }
    }

    private static int firstLine(MethodNode m) {
        for (AbstractInsnNode insn : m.instructions) {
            if (insn instanceof LineNumberNode ln) {
                return ln.line;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Set<AcquiredCar> resolveCars(MethodDataFlow flow, TrackedValue value) {
        Set<AcquiredCar> cars = new LinkedHashSet<>();
        for (Resolved r : resolver().resolve(flow, value)) {
            cars.add(new AcquiredCar(r.concreteClass(), r.declaredType(), r.origin()));
        }
        return cars;
    }

    private ValueResolver resolver() {
        if (resolver == null) {
            resolver = new ValueResolver(hierarchy, repo, this::isTargetType,
                    ValueResolver.callersFromEdges(result.edges(), repo));
        }
        return resolver;
    }

    private boolean isTargetType(String type) {
        return type != null && result.targets().stream().anyMatch(t -> hierarchy.isSubtypeOf(type, t));
    }

    private String concreteOf(String type) {
        return resolver().concreteOf(type);
    }

    private static String simple(String internalName) {
        return MethodRef.simpleName(internalName);
    }
}
