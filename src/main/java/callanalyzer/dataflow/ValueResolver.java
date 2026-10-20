package callanalyzer.dataflow;

import callanalyzer.analysis.CallEdge;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.dataflow.ValueSource.CallResult;
import callanalyzer.dataflow.ValueSource.Constant;
import callanalyzer.dataflow.ValueSource.FieldValue;
import callanalyzer.dataflow.ValueSource.NewObject;
import callanalyzer.dataflow.ValueSource.Parameter;
import callanalyzer.dataflow.ValueSource.This;
import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Answers "which objects of an element type can this value be, or contain?" with an intra-procedural data-flow
 * analysis that follows values back to {@code new X()}, constants, fields (by declared type, constant value or
 * assignments), method return values, collection factories ({@code List.of}, {@code Arrays.asList}, ...),
 * {@code add}/{@code addAll} calls, arrays, fields holding collections, and parameters (through the callers supplied
 * by a {@link CallerLookup}).
 *
 * <p>The element type is given as a predicate on internal type names, e.g. "implements ICar" or
 * "is {@code java/lang/String}". Results are memoised, so create a new resolver when the callers change.
 */
public final class ValueResolver {

    /**
     * An element found.
     *
     * @param concreteClass the element's class (internal name), {@code null} if it cannot be determined statically
     * @param declaredType  the static type the element was found with (internal name)
     * @param constant      the constant value for constant elements (e.g. a {@link String}), otherwise {@code null}
     * @param origin        human readable description of where the element comes from
     */
    public record Resolved(String concreteClass, String declaredType, Object constant, String origin) {
    }

    /** A call instruction that invokes a given method. */
    public record CallerSite(MethodDataFlow flow, MethodInsnNode call) {
    }

    /** An {@code invokedynamic} instruction creating a lambda or method reference. */
    public record LambdaSite(MethodDataFlow flow, InvokeDynamicInsnNode indy) {
    }

    /** What the resolver knows about the analysed flow. */
    public interface CallerLookup {
        /** The call sites invoking {@code callee}, used to resolve parameters. */
        List<CallerSite> callersOf(MethodRef callee);

        /** The {@code invokedynamic} sites creating the lambda whose body is {@code lambdaBody}. */
        default List<LambdaSite> lambdaSitesOf(MethodRef lambdaBody) {
            return List.of();
        }

        /**
         * Whether {@code method} runs in the analysed flow. Elements added to a field's collection (or values stored
         * into a field) only count if they happen in such a method, or in a constructor / static initializer.
         */
        default boolean inScope(MethodRef method) {
            return true;
        }
    }

    private static final int MAX_DEPTH = 10;
    private static final Set<String> MUTATORS = Set.of("<init>", "add", "addAll", "addFirst", "addLast", "push",
            "offer", "offerFirst", "offerLast", "put", "putIfAbsent", "set");

    private final ClassHierarchy hierarchy;
    private final BytecodeRepository repo;
    private final Predicate<String> isElementType;
    private final CallerLookup callers;
    private final Map<List<Object>, Set<Resolved>> memo = new HashMap<>();
    private final Set<List<Object>> inProgress = new HashSet<>();

    public ValueResolver(ClassHierarchy hierarchy, BytecodeRepository repo, Predicate<String> isElementType,
                         CallerLookup callers) {
        this.hierarchy = hierarchy;
        this.repo = repo;
        this.isElementType = isElementType;
        this.callers = callers;
    }

    /**
     * Callers taken from the call graph edges explored so far; the methods of those edges are the flow's scope.
     * Lambda bodies are resolved through the {@code invokedynamic} sites creating them.
     */
    public static CallerLookup callersFromEdges(Map<MethodRef, List<CallEdge>> edges, BytecodeRepository repo) {
        return new CallerLookup() {
            @Override
            public List<CallerSite> callersOf(MethodRef callee) {
                List<CallerSite> sites = new ArrayList<>();
                for (Map.Entry<MethodRef, List<CallEdge>> e : edges.entrySet()) {
                    for (CallEdge edge : e.getValue()) {
                        if (edge.site().viaLambda() || edge.targets().stream().noneMatch(t -> t.method().equals(callee))) {
                            continue;
                        }
                        repo.flow(e.getKey()).ifPresent(flow -> BytecodeRepository
                                .callInsns(flow, edge.site().target(), edge.site().line())
                                .forEach(call -> sites.add(new CallerSite(flow, call))));
                    }
                }
                return sites;
            }

            @Override
            public List<LambdaSite> lambdaSitesOf(MethodRef lambdaBody) {
                List<LambdaSite> sites = new ArrayList<>();
                for (Map.Entry<MethodRef, List<CallEdge>> e : edges.entrySet()) {
                    for (CallEdge edge : e.getValue()) {
                        if (edge.site().viaLambda() && edge.site().target().equals(lambdaBody)) {
                            repo.flow(e.getKey()).ifPresent(flow -> BytecodeRepository
                                    .indyInsns(flow, lambdaBody, edge.site().line())
                                    .forEach(indy -> sites.add(new LambdaSite(flow, indy))));
                        }
                    }
                }
                return sites;
            }

            @Override
            public boolean inScope(MethodRef method) {
                return edges.containsKey(method);
            }
        };
    }

    /**
     * The objects a lambda / method reference created by {@code indy} is applied to: the elements of the collection,
     * stream or optional it is handed to ({@code events.forEach(dispatcher::dispatch)},
     * {@code events.stream().filter(..).forEach(e -> ..)}, {@code opt.ifPresent(..)}).
     */
    public Set<Resolved> elementsPassedTo(MethodDataFlow flow, InvokeDynamicInsnNode indy) {
        return elementsPassedTo(flow, indy, 0);
    }

    private Set<Resolved> elementsPassedTo(MethodDataFlow flow, InvokeDynamicInsnNode indy, int depth) {
        Set<Resolved> found = new LinkedHashSet<>();
        ValueSource lambda = new ValueSource.LambdaValue(flow.indexOf(indy));
        for (AbstractInsnNode insn : flow.instructions()) {
            if (!(insn instanceof MethodInsnNode consumer)) {
                continue;
            }
            List<TrackedValue> args = flow.arguments(consumer);
            if (args.stream().noneMatch(a -> a.sources().contains(lambda))) {
                continue;
            }
            TrackedValue receiver = flow.receiver(consumer);
            if (receiver != null) {
                found.addAll(resolveAny(flow, receiver, depth + 1));
            }
            for (TrackedValue a : args) {
                if (!a.sources().contains(lambda)) {
                    found.addAll(resolveAny(flow, a, depth + 1)); // static helpers: forEach(list, consumer)
                }
            }
        }
        return found;
    }

    /** Every element-typed object {@code value} may be, or contain (if it is a collection, array, ...). */
    public Set<Resolved> resolve(MethodDataFlow flow, TrackedValue value) {
        return resolveAny(flow, value, 0);
    }

    /** The class an instance of {@code type} must be: the type itself if concrete, or its only concrete subtype. */
    public String concreteOf(String type) {
        ClassInfo c = hierarchy.get(type);
        if (c != null && c.isConcrete()) {
            return type;
        }
        List<String> concrete = hierarchy.allSubtypes(type).stream()
                .filter(s -> hierarchy.get(s) != null && hierarchy.get(s).isConcrete())
                .toList();
        return concrete.size() == 1 ? concrete.get(0) : null;
    }

    private Set<Resolved> resolveAny(MethodDataFlow flow, TrackedValue value, int depth) {
        Set<Resolved> found = new LinkedHashSet<>();
        if (value == null || depth > MAX_DEPTH) {
            return found;
        }
        for (ValueSource src : value.sources()) {
            List<Object> key = List.of(flow.method(), src);
            Set<Resolved> cached = memo.get(key);
            if (cached != null) {
                found.addAll(cached);
                continue;
            }
            if (!inProgress.add(key)) {
                continue; // cycle
            }
            String type = typeOf(flow, src);
            Set<Resolved> r = type != null && isElementType.test(type)
                    ? resolveObject(flow, src, depth)
                    : resolveContainer(flow, src, depth);
            inProgress.remove(key);
            memo.put(key, r);
            found.addAll(r);
        }
        return found;
    }

    /** {@code src} is itself an element: find its class (or constant value). */
    private Set<Resolved> resolveObject(MethodDataFlow flow, ValueSource src, int depth) {
        Set<Resolved> found = new LinkedHashSet<>();
        if (src instanceof NewObject n) {
            found.add(new Resolved(n.type(), n.type(), null,
                    "new " + simple(n.type()) + "()" + at(flow, flow.insn(n.insn()))));
        } else if (src instanceof Constant c) {
            String text = c.value() instanceof Type t ? simple(t.getInternalName()) + ".class" : "\"" + c.value() + "\"";
            found.add(new Resolved(null, typeOf(flow, src), c.value(), "sabit " + text + at(flow, null)));
        } else if (src instanceof FieldValue f) {
            String declared = Type.getType(f.desc()).getInternalName();
            String origin = "alan " + simple(f.owner()) + "." + f.name() + " : " + simple(declared);
            Object constant = repo.field(f.owner(), f.name()).map(fn -> fn.value).orElse(null);
            String concrete = concreteOf(declared);
            if (constant != null) {
                found.add(new Resolved(null, declared, constant, "sabit " + simple(f.owner()) + "." + f.name()
                        + " = \"" + constant + "\""));
            } else if (concrete != null) {
                found.add(new Resolved(concrete, declared, null, origin));
            } else {
                for (Assignment a : fieldAssignments(f)) {
                    found.addAll(resolveAny(a.flow(), a.value(), depth + 1));
                }
                if (found.isEmpty()) {
                    found.add(new Resolved(null, declared, null, origin));
                }
            }
        } else if (src instanceof CallResult c) {
            for (MethodDataFlow body : calleeBodies(flow, c)) {
                for (TrackedValue rv : body.returnValues()) {
                    found.addAll(resolveAny(body, rv, depth + 1));
                }
            }
            if (found.isEmpty()) {
                String declared = Type.getReturnType(c.method().desc()).getInternalName();
                found.add(new Resolved(concreteOf(declared), declared, null, c.method().toShortString()
                        + " donus degeri" + at(flow, flow.insn(c.insn()))));
            }
        } else if (src instanceof Parameter p) {
            found.addAll(resolveFromCallers(flow, p, depth));
            if (found.isEmpty()) {
                String declared = Type.getType(p.desc()).getInternalName();
                found.add(new Resolved(concreteOf(declared), declared, null, parameterText(flow, p)));
            }
        } else if (src instanceof This) {
            String owner = flow.method().owner();
            found.add(new Resolved(concreteOf(owner), owner, null, "this (" + simple(owner) + ")"));
        }
        return found;
    }

    /** {@code src} is a collection, array or other holder: find the elements put into it. */
    private Set<Resolved> resolveContainer(MethodDataFlow flow, ValueSource src, int depth) {
        Set<Resolved> found = new LinkedHashSet<>(mutationsOf(flow, src, depth));
        if (src instanceof FieldValue f) {
            for (Assignment a : fieldAssignments(f)) {
                found.addAll(resolveAny(a.flow(), a.value(), depth + 1));
            }
            ClassNode owner = repo.classNode(f.owner()).orElse(null);
            if (owner != null) {
                for (MethodNode m : owner.methods) {
                    MethodRef ref = new MethodRef(owner.name, m.name, m.desc);
                    if (!inScope(ref)) {
                        continue; // e.g. scrap() adding ADomainEvent is not part of this flow
                    }
                    MethodDataFlow other = repo.flow(ref).orElse(null);
                    if (other != null && !other.method().equals(flow.method())) {
                        found.addAll(mutationsOf(other, src, depth + 1));
                    }
                }
            }
        } else if (src instanceof CallResult c) {
            List<MethodDataFlow> bodies = calleeBodies(flow, c);
            for (MethodDataFlow body : bodies) {
                for (TrackedValue rv : body.returnValues()) {
                    found.addAll(resolveAny(body, rv, depth + 1));
                }
            }
            if (bodies.isEmpty()) {
                // Library call such as List.of(a, b), Arrays.asList(arr), list.stream().toList(), list.get(0):
                // the result is made of the receiver and the arguments.
                MethodInsnNode call = (MethodInsnNode) flow.insn(c.insn());
                found.addAll(resolveAny(flow, flow.receiver(call), depth + 1));
                for (TrackedValue arg : flow.arguments(call)) {
                    found.addAll(resolveAny(flow, arg, depth + 1));
                }
            }
        } else if (src instanceof Parameter p) {
            found.addAll(resolveFromCallers(flow, p, depth));
        }
        return found;
    }

    /** Elements added to {@code src} inside {@code flow}: {@code add/addAll/...}, constructors, array stores. */
    private Set<Resolved> mutationsOf(MethodDataFlow flow, ValueSource src, int depth) {
        Set<Resolved> found = new LinkedHashSet<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (insn instanceof MethodInsnNode m) {
                TrackedValue receiver = flow.receiver(m);
                List<TrackedValue> args = flow.arguments(m);
                if (receiver != null && receiver.sources().contains(src) && MUTATORS.contains(m.name)) {
                    args.forEach(a -> found.addAll(resolveAny(flow, a, depth + 1)));
                } else if (m.getOpcode() == Opcodes.INVOKESTATIC && m.name.equals("addAll")
                        && args.stream().anyMatch(a -> a.sources().contains(src))) {
                    // Collections.addAll(list, a, b, c)
                    args.stream().filter(a -> !a.sources().contains(src))
                            .forEach(a -> found.addAll(resolveAny(flow, a, depth + 1)));
                }
            } else if (insn.getOpcode() == Opcodes.AASTORE) {
                TrackedValue array = flow.stack(insn, 2);
                if (array != null && array.sources().contains(src)) {
                    found.addAll(resolveAny(flow, flow.stack(insn, 0), depth + 1));
                }
            }
        }
        return found;
    }

    private Set<Resolved> resolveFromCallers(MethodDataFlow flow, Parameter p, int depth) {
        Set<Resolved> found = new LinkedHashSet<>();
        int index = flow.parameterIndex(p.local());
        if (index < 0) {
            return found;
        }
        for (CallerSite caller : callers.callersOf(flow.method())) {
            List<TrackedValue> args = caller.flow().arguments(caller.call());
            if (index < args.size()) {
                found.addAll(resolveAny(caller.flow(), args.get(index), depth + 1));
            }
        }
        // A lambda body's parameters are the captured values followed by the arguments of the functional method.
        boolean instanceLambda = hierarchy.method(flow.method()).map(m -> !m.isStatic()).orElse(false);
        int offset = instanceLambda ? 1 : 0; // a lambda using "this" captures it first, as its receiver
        for (LambdaSite site : callers.lambdaSitesOf(flow.method())) {
            int captured = Type.getArgumentTypes(site.indy().desc).length;
            if (index + offset < captured) {
                found.addAll(resolveAny(site.flow(), site.flow().stack(site.indy(), captured - 1 - index - offset),
                        depth + 1));
            } else {
                found.addAll(elementsPassedTo(site.flow(), site.indy(), depth + 1));
            }
        }
        return found;
    }

    private record Assignment(MethodDataFlow flow, TrackedValue value) {
    }

    /** Values stored into a field by the methods of its declaring class (constructors, setters, init methods). */
    private List<Assignment> fieldAssignments(FieldValue f) {
        List<Assignment> found = new ArrayList<>();
        ClassNode owner = repo.classNode(f.owner()).orElse(null);
        if (owner == null) {
            return found;
        }
        for (MethodNode m : owner.methods) {
            MethodRef ref = new MethodRef(owner.name, m.name, m.desc);
            MethodDataFlow flow = inScope(ref) ? repo.flow(ref).orElse(null) : null;
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
        CallSite site = new CallSite(BytecodeRepository.kindOf(call), c.method(), -1, false);
        List<MethodDataFlow> bodies = new ArrayList<>();
        for (DispatchTarget t : hierarchy.dispatch(site)) {
            repo.flow(t.method()).ifPresent(bodies::add);
        }
        return bodies;
    }

    /** Constructors and static initializers set objects up before any flow runs, so they are always in scope. */
    private boolean inScope(MethodRef method) {
        return method.isConstructor() || method.name().equals("<clinit>") || callers.inScope(method);
    }

    private static String typeOf(MethodDataFlow flow, ValueSource src) {
        Type t;
        if (src instanceof NewObject n) {
            t = n.type().startsWith("[") ? Type.getType(n.type()) : Type.getObjectType(n.type());
        } else if (src instanceof FieldValue f) {
            t = Type.getType(f.desc());
        } else if (src instanceof CallResult c) {
            t = Type.getReturnType(c.method().desc());
        } else if (src instanceof Parameter p) {
            t = Type.getType(p.desc());
        } else if (src instanceof Constant c) {
            t = Type.getObjectType(c.value() instanceof Type ? "java/lang/Class" : "java/lang/String");
        } else if (src instanceof ValueSource.LambdaValue) {
            return null;
        } else {
            t = Type.getObjectType(flow.method().owner());
        }
        return t.getSort() == Type.OBJECT ? t.getInternalName() : null;
    }

    private static String at(MethodDataFlow flow, AbstractInsnNode insn) {
        int line = insn == null ? -1 : flow.lineOf(insn);
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
