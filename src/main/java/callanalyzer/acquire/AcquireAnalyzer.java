package callanalyzer.acquire;

import callanalyzer.acquire.ValueSource.CallResult;
import callanalyzer.acquire.ValueSource.FieldValue;
import callanalyzer.acquire.ValueSource.NewObject;
import callanalyzer.acquire.ValueSource.Parameter;
import callanalyzer.acquire.ValueSource.This;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallEdge;
import callanalyzer.analysis.ChainStep;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.analysis.Finding;
import callanalyzer.analysis.MethodSpec;
import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.InvokeKind;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Finds the calls of an "acquirer" method (e.g. {@code CarAcquirer.acquire(List<ICar>)}) in an analysed flow and
 * determines which target implementations are put into the list it receives; and/or reads the acquire annotation
 * on the entry methods (e.g. {@code @AcquireCar(cars = {Opel.class, Mercedes.class})}). The acquired classes are
 * then compared with the implementations the flow actually calls.
 *
 * <p>The list contents are recovered with an intra-procedural data-flow analysis that follows values back to
 * {@code new X()}, fields (by their declared type, e.g. {@code @Autowired Bmw bmw}), method return values,
 * collection factories ({@code List.of}, {@code Arrays.asList}, ...), {@code add}/{@code addAll} calls, arrays,
 * fields holding a list, and parameters (through the callers found in the analysed flow).
 */
public final class AcquireAnalyzer {

    private static final int MAX_DEPTH = 10;
    private static final Set<String> MUTATORS = Set.of("<init>", "add", "addAll", "addFirst", "addLast", "push",
            "offer", "offerFirst", "offerLast", "put", "putIfAbsent", "set");

    private final ClassHierarchy hierarchy;
    private final Map<String, byte[]> classBytes;
    private final AnalysisResult result;
    private final MethodSpec acquirerSpec;
    private final String acquirerClass;
    private final String annotationType;

    private final Map<String, Optional<ClassNode>> classNodes = new HashMap<>();
    private final Map<MethodRef, Optional<MethodDataFlow>> flows = new HashMap<>();
    private final Map<List<Object>, Set<AcquiredCar>> memo = new HashMap<>();
    private final Set<List<Object>> inProgress = new HashSet<>();

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
        this.classBytes = classBytes;
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
                flow(e.getKey()).ifPresent(flow -> calls.addAll(acquireCallsIn(flow)));
            }
        }

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
        return compare(calls, annotations, acquired, unresolved, acquirerMethods);
    }

    // ------------------------------------------------------------------------------------------------ comparison

    private AcquireComparison compare(List<AcquireCall> calls, List<AnnotationCheck> annotations, Set<String> acquired,
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
                List.copyOf(calls), List.copyOf(annotations), List.copyOf(acquired),
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
            CallSite site = new CallSite(kindOf(m), called, -1, false);
            if (!isAcquirerCall(site, hierarchy.dispatch(site))) {
                continue;
            }
            Set<AcquiredCar> cars = new LinkedHashSet<>();
            for (TrackedValue arg : flow.arguments(m)) {
                cars.addAll(resolveAny(flow, arg, 0));
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
            ClassNode owner = classNode(entry.owner()).orElse(null);
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

    // ------------------------------------------------------------------------------------- value resolution

    /** Every target-typed object a value may be, or contain (if it is a collection/array). */
    private Set<AcquiredCar> resolveAny(MethodDataFlow flow, TrackedValue value, int depth) {
        Set<AcquiredCar> cars = new LinkedHashSet<>();
        if (value == null || depth > MAX_DEPTH) {
            return cars;
        }
        for (ValueSource src : value.sources()) {
            List<Object> key = List.of(flow.method(), src);
            Set<AcquiredCar> cached = memo.get(key);
            if (cached != null) {
                cars.addAll(cached);
                continue;
            }
            if (!inProgress.add(key)) {
                continue; // cycle
            }
            Set<AcquiredCar> found = isTargetType(typeOf(flow, src))
                    ? resolveObject(flow, src, depth)
                    : resolveContainer(flow, src, depth);
            inProgress.remove(key);
            memo.put(key, found);
            cars.addAll(found);
        }
        return cars;
    }

    /** {@code src} is itself a target-typed object (e.g. an {@code ICar}): find its class. */
    private Set<AcquiredCar> resolveObject(MethodDataFlow flow, ValueSource src, int depth) {
        Set<AcquiredCar> cars = new LinkedHashSet<>();
        if (src instanceof NewObject n) {
            cars.add(new AcquiredCar(n.type(), n.type(),
                    "new " + simple(n.type()) + "()" + at(flow, flow.insn(n.insn()))));
        } else if (src instanceof FieldValue f) {
            String declared = Type.getType(f.desc()).getInternalName();
            String origin = "alan " + simple(f.owner()) + "." + f.name() + " : " + simple(declared);
            String concrete = concreteOf(declared);
            if (concrete != null) {
                cars.add(new AcquiredCar(concrete, declared, origin));
            } else {
                for (Assignment a : fieldAssignments(f)) {
                    cars.addAll(resolveAny(a.flow(), a.value(), depth + 1));
                }
                if (cars.isEmpty()) {
                    cars.add(new AcquiredCar(null, declared, origin));
                }
            }
        } else if (src instanceof CallResult c) {
            for (MethodDataFlow body : calleeBodies(flow, c)) {
                for (TrackedValue rv : body.returnValues()) {
                    cars.addAll(resolveAny(body, rv, depth + 1));
                }
            }
            if (cars.isEmpty()) {
                String declared = Type.getReturnType(c.method().desc()).getInternalName();
                cars.add(new AcquiredCar(concreteOf(declared), declared, c.method().toShortString()
                        + " donus degeri" + at(flow, flow.insn(c.insn()))));
            }
        } else if (src instanceof Parameter p) {
            cars.addAll(resolveFromCallers(flow, p, depth));
            if (cars.isEmpty()) {
                String declared = Type.getType(p.desc()).getInternalName();
                cars.add(new AcquiredCar(concreteOf(declared), declared, parameterText(flow, p)));
            }
        } else if (src instanceof This) {
            String owner = flow.method().owner();
            cars.add(new AcquiredCar(concreteOf(owner), owner, "this (" + simple(owner) + ")"));
        }
        return cars;
    }

    /** {@code src} is a collection, array or other holder: find the target-typed objects put into it. */
    private Set<AcquiredCar> resolveContainer(MethodDataFlow flow, ValueSource src, int depth) {
        Set<AcquiredCar> cars = new LinkedHashSet<>(mutationsOf(flow, src, depth));
        if (src instanceof FieldValue f) {
            for (Assignment a : fieldAssignments(f)) {
                cars.addAll(resolveAny(a.flow(), a.value(), depth + 1));
            }
            ClassNode owner = classNode(f.owner()).orElse(null);
            if (owner != null) {
                for (MethodNode m : owner.methods) {
                    MethodDataFlow other = flow(new MethodRef(owner.name, m.name, m.desc)).orElse(null);
                    if (other != null && !other.method().equals(flow.method())) {
                        cars.addAll(mutationsOf(other, src, depth + 1));
                    }
                }
            }
        } else if (src instanceof CallResult c) {
            List<MethodDataFlow> bodies = calleeBodies(flow, c);
            for (MethodDataFlow body : bodies) {
                for (TrackedValue rv : body.returnValues()) {
                    cars.addAll(resolveAny(body, rv, depth + 1));
                }
            }
            if (bodies.isEmpty()) {
                // Library call such as List.of(a, b), Arrays.asList(arr), list.stream().toList(), list.get(0):
                // the result is made of the receiver and the arguments.
                MethodInsnNode call = (MethodInsnNode) flow.insn(c.insn());
                cars.addAll(resolveAny(flow, flow.receiver(call), depth + 1));
                for (TrackedValue arg : flow.arguments(call)) {
                    cars.addAll(resolveAny(flow, arg, depth + 1));
                }
            }
        } else if (src instanceof Parameter p) {
            cars.addAll(resolveFromCallers(flow, p, depth));
        }
        return cars;
    }

    /** Elements added to {@code src} inside {@code flow}: {@code add/addAll/...}, constructors, array stores. */
    private Set<AcquiredCar> mutationsOf(MethodDataFlow flow, ValueSource src, int depth) {
        Set<AcquiredCar> cars = new LinkedHashSet<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (insn instanceof MethodInsnNode m) {
                TrackedValue receiver = flow.receiver(m);
                List<TrackedValue> args = flow.arguments(m);
                if (receiver != null && receiver.sources().contains(src) && MUTATORS.contains(m.name)) {
                    args.forEach(a -> cars.addAll(resolveAny(flow, a, depth + 1)));
                } else if (m.getOpcode() == Opcodes.INVOKESTATIC && m.name.equals("addAll")
                        && args.stream().anyMatch(a -> a.sources().contains(src))) {
                    // Collections.addAll(list, a, b, c)
                    args.stream().filter(a -> !a.sources().contains(src))
                            .forEach(a -> cars.addAll(resolveAny(flow, a, depth + 1)));
                }
            } else if (insn.getOpcode() == Opcodes.AASTORE) {
                TrackedValue array = flow.stack(insn, 2);
                if (array != null && array.sources().contains(src)) {
                    cars.addAll(resolveAny(flow, flow.stack(insn, 0), depth + 1));
                }
            }
        }
        return cars;
    }

    private Set<AcquiredCar> resolveFromCallers(MethodDataFlow flow, Parameter p, int depth) {
        Set<AcquiredCar> cars = new LinkedHashSet<>();
        int index = flow.parameterIndex(p.local());
        if (index < 0) {
            return cars;
        }
        for (Map.Entry<MethodRef, List<CallEdge>> e : result.edges().entrySet()) {
            for (CallEdge edge : e.getValue()) {
                if (edge.site().viaLambda()
                        || edge.targets().stream().noneMatch(t -> t.method().equals(flow.method()))) {
                    continue;
                }
                MethodDataFlow caller = flow(e.getKey()).orElse(null);
                if (caller == null) {
                    continue;
                }
                for (MethodInsnNode call : callInsns(caller, edge.site())) {
                    List<TrackedValue> args = caller.arguments(call);
                    if (index < args.size()) {
                        cars.addAll(resolveAny(caller, args.get(index), depth + 1));
                    }
                }
            }
        }
        return cars;
    }

    private record Assignment(MethodDataFlow flow, TrackedValue value) {
    }

    /** Values stored into a field by the methods of its declaring class (constructors, setters, init methods). */
    private List<Assignment> fieldAssignments(FieldValue f) {
        List<Assignment> found = new ArrayList<>();
        ClassNode owner = classNode(f.owner()).orElse(null);
        if (owner == null) {
            return found;
        }
        for (MethodNode m : owner.methods) {
            MethodDataFlow flow = flow(new MethodRef(owner.name, m.name, m.desc)).orElse(null);
            if (flow == null) {
                continue;
            }
            for (AbstractInsnNode insn : flow.instructions()) {
                if ((insn.getOpcode() == Opcodes.PUTFIELD || insn.getOpcode() == Opcodes.PUTSTATIC)
                        && insn instanceof FieldInsnNode put
                        && put.owner.equals(f.owner()) && put.name.equals(f.name())) {
                    TrackedValue v = flow.stack(insn, 0);
                    if (v != null) {
                        found.add(new Assignment(flow, v));
                    }
                }
            }
        }
        return found;
    }

    /** Bodies of the loaded methods a call may run (empty for library calls). */
    private List<MethodDataFlow> calleeBodies(MethodDataFlow flow, CallResult c) {
        MethodInsnNode call = (MethodInsnNode) flow.insn(c.insn());
        CallSite site = new CallSite(kindOf(call), c.method(), -1, false);
        List<MethodDataFlow> bodies = new ArrayList<>();
        for (DispatchTarget t : hierarchy.dispatch(site)) {
            flow(t.method()).ifPresent(bodies::add);
        }
        return bodies;
    }

    private List<MethodInsnNode> callInsns(MethodDataFlow flow, CallSite site) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (insn instanceof MethodInsnNode m && m.owner.equals(site.target().owner())
                    && m.name.equals(site.target().name()) && m.desc.equals(site.target().desc())
                    && (site.line() < 0 || flow.lineOf(m) == site.line())) {
                calls.add(m);
            }
        }
        return calls;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private String typeOf(MethodDataFlow flow, ValueSource src) {
        Type t;
        if (src instanceof NewObject n) {
            t = n.type().startsWith("[") ? Type.getType(n.type()) : Type.getObjectType(n.type());
        } else if (src instanceof FieldValue f) {
            t = Type.getType(f.desc());
        } else if (src instanceof CallResult c) {
            t = Type.getReturnType(c.method().desc());
        } else if (src instanceof Parameter p) {
            t = Type.getType(p.desc());
        } else {
            t = Type.getObjectType(flow.method().owner());
        }
        return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
    }

    private boolean isTargetType(String type) {
        return type != null && result.targets().stream().anyMatch(t -> hierarchy.isSubtypeOf(type, t));
    }

    /** The class an instance of {@code type} must be: the type itself if concrete, or its only concrete subtype. */
    private String concreteOf(String type) {
        ClassInfo c = hierarchy.get(type);
        if (c != null && c.isConcrete()) {
            return type;
        }
        List<String> concrete = hierarchy.allSubtypes(type).stream()
                .filter(s -> hierarchy.get(s) != null && hierarchy.get(s).isConcrete())
                .toList();
        return concrete.size() == 1 ? concrete.get(0) : null;
    }

    private Optional<MethodDataFlow> flow(MethodRef ref) {
        return flows.computeIfAbsent(ref, r -> classNode(r.owner()).flatMap(cn -> cn.methods.stream()
                .filter(m -> m.name.equals(r.name()) && m.desc.equals(r.desc()))
                .findFirst()
                .flatMap(m -> MethodDataFlow.analyze(cn, m))));
    }

    private Optional<ClassNode> classNode(String internalName) {
        return classNodes.computeIfAbsent(internalName, n -> {
            byte[] bytes = classBytes.get(n);
            if (bytes == null) {
                return Optional.empty();
            }
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
            return Optional.of(cn);
        });
    }

    private static InvokeKind kindOf(MethodInsnNode m) {
        return switch (m.getOpcode()) {
            case Opcodes.INVOKESTATIC -> InvokeKind.STATIC;
            case Opcodes.INVOKESPECIAL -> InvokeKind.SPECIAL;
            case Opcodes.INVOKEINTERFACE -> InvokeKind.INTERFACE;
            default -> InvokeKind.VIRTUAL;
        };
    }

    private static String at(MethodDataFlow flow, AbstractInsnNode insn) {
        int line = flow.lineOf(insn);
        return " (" + (flow.sourceFile() == null ? "?" : flow.sourceFile()) + (line > 0 ? ":" + line : "") + ")";
    }

    private static String parameterText(MethodDataFlow flow, Parameter p) {
        String name = flow.parameterName(p.local());
        return flow.method().toShortString() + " metodunun " + (flow.parameterIndex(p.local()) + 1)
                + ". parametresi" + (name == null ? "" : " '" + name + "'");
    }

    private static String simple(String internalName) {
        return MethodRef.simpleName(internalName);
    }
}
