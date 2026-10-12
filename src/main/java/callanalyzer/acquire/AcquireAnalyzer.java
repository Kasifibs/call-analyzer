package callanalyzer.acquire;

import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallEdge;
import callanalyzer.analysis.ChainStep;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.analysis.Finding;
import callanalyzer.analysis.MethodSpec;
import callanalyzer.dataflow.BytecodeRepository;
import callanalyzer.dataflow.MethodDataFlow;
import callanalyzer.dataflow.TrackedValue;
import callanalyzer.dataflow.ValueResolver;
import callanalyzer.dataflow.ValueResolver.Resolved;
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
        if (acquirerSpec == null && annotationType == null) {
            throw new IllegalArgumentException("Acquire metodu ya da anotasyonu verilmeli.");
        }
        this.result = result;
        this.hierarchy = result.hierarchy();
        this.repo = new BytecodeRepository(classBytes);
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
                }
            }
            if (callsAcquirer) {
                repo.flow(e.getKey()).ifPresent(flow -> calls.addAll(acquireCallsIn(flow)));
            }
        }

        // Like the annotation, only the entry method's own acquire calls count: an acquire made somewhere deeper in the
        // flow (a helper, an event observer, ...) must not hide classes the entry method itself failed to acquire.
        Set<MethodRef> entries = new HashSet<>(result.entries());
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
        return compare(calls, otherCalls, annotations, acquired, unresolved, acquirerMethods);
    }

    // ------------------------------------------------------------------------------------------------ comparison

    private AcquireComparison compare(List<AcquireCall> calls, List<AcquireCall> otherCalls,
                                      List<AnnotationCheck> annotations, Set<String> acquired,
                                      List<AcquiredCar> unresolved, Set<MethodRef> acquirerMethods) {
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
        for (String cls : used) {
            ClassUsage usage = new ClassUsage(cls, certain.getOrDefault(cls, List.of()),
                    possible.getOrDefault(cls, List.of()));
            if (acquired.stream().anyMatch(a -> hierarchy.isSubtypeOf(cls, a))) {
                covered.add(usage);
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
                List.copyOf(unresolved), covered, missing, possiblyMissing, undecided, unused);
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

    private boolean isAcquirerCall(CallSite site, List<DispatchTarget> targets) {
        if (acquirerSpec == null || !acquirerSpec.matchesSignature(site.target())) {
            return false;
        }
        return hierarchy.isSubtypeOf(site.target().owner(), acquirerClass)
                || targets.stream().anyMatch(t -> hierarchy.isSubtypeOf(t.method().owner(), acquirerClass));
    }

    private List<AcquireCall> acquireCallsIn(MethodDataFlow flow) {
        List<AcquireCall> calls = new ArrayList<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (!(insn instanceof MethodInsnNode m)) {
                continue;
            }
            MethodRef called = new MethodRef(m.owner, m.name, m.desc);
            CallSite site = new CallSite(BytecodeRepository.kindOf(m), called, -1, false);
            if (!isAcquirerCall(site, hierarchy.dispatch(site))) {
                continue;
            }
            Set<AcquiredCar> cars = new LinkedHashSet<>();
            for (TrackedValue arg : flow.arguments(m)) {
                cars.addAll(resolveCars(flow, arg));
            }
            calls.add(new AcquireCall(flow.method(), flow.sourceFile(), flow.lineOf(insn), called, List.copyOf(cars)));
        }
        return calls;
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
