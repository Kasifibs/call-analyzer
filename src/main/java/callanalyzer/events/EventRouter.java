package callanalyzer.events;

import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.dataflow.BytecodeRepository;
import callanalyzer.dataflow.MethodDataFlow;
import callanalyzer.dataflow.TrackedValue;
import callanalyzer.dataflow.ValueResolver;
import callanalyzer.dataflow.ValueResolver.CallerLookup;
import callanalyzer.dataflow.ValueResolver.Resolved;
import callanalyzer.dataflow.ValueSource;
import callanalyzer.dataflow.ValueSource.CallResult;
import callanalyzer.dataflow.ValueSource.Constant;
import callanalyzer.dataflow.ValueSource.FieldValue;
import callanalyzer.dataflow.ValueSource.NewObject;
import callanalyzer.dataflow.ValueSource.Parameter;
import callanalyzer.dataflow.ValueSource.This;
import callanalyzer.events.EventDispatch.Resolution;
import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Routes event dispatch calls to the observers that are registered for the event.
 *
 * <p>Without it, {@code dispatcher.dispatch(event)} leads (through {@code observer.handle(event)} inside the
 * dispatcher) to every observer of the application. The router instead:
 * <ol>
 *   <li>scans all loaded classes for register calls and builds the registration table. A call with an event name
 *       ({@code register("CAR_RENTED", this)}) registers the observer for that name; a call without one
 *       ({@code addObserver(this)}) registers it for the event type it handles, taken from {@code IObserver<AEvent>}
 *       (the parameter of its {@code handle} method, or the generic signature of the class);</li>
 *   <li>at a dispatch call, finds the class and name of the published event (constants passed to the event's
 *       constructor or to {@code super(...)}, {@code getName()} overrides returning a constant, ...);</li>
 *   <li>continues the flow only into the observers registered for that name or that type. An observer whose
 *       {@code handle} parameter cannot accept the event class is never selected. If nothing is known about the event,
 *       every registered observer is selected.</li>
 * </ol>
 */
public final class EventRouter {

    private final ClassHierarchy hierarchy;
    private final BytecodeRepository repo;
    private final String eventClass;
    private final String dispatcherClass;
    private final String observerClass;
    private final String handleMethod;

    private final Map<String, Set<String>> observersByName = new TreeMap<>();
    private final Set<String> observersForAnyName = new TreeSet<>();
    /** Observers registered without a name, and the event type each one handles. */
    private final Map<String, String> observerTypes = new TreeMap<>();
    private final boolean exactTypeMatch;
    private final List<Registration> registrations = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public EventRouter(ClassHierarchy hierarchy, BytecodeRepository repo, EventConfig config) {
        this.hierarchy = hierarchy;
        this.repo = repo;
        this.eventClass = hierarchy.resolveClassName(config.eventClass());
        this.dispatcherClass = hierarchy.resolveClassName(config.dispatcherClass());
        this.observerClass = hierarchy.resolveClassName(config.observerClass());
        this.handleMethod = config.handleMethod();
        this.exactTypeMatch = config.exactTypeMatch();
        for (String[] t : new String[][]{{eventClass, "--event-class"}, {dispatcherClass, "--event-dispatcher"},
                {observerClass, "--event-observer"}}) {
            if (!hierarchy.contains(t[0])) {
                warnings.add(t[1] + " sinif yolunda bulunamadi: " + MethodRef.toDottedName(t[0]));
            }
        }
        buildRegistry();
        Set<String> unregistered = concreteObservers();
        unregistered.removeAll(registeredObservers());
        if (!registrations.isEmpty() && !unregistered.isEmpty()) {
            warnings.add("Kaydi bulunamayan observer'lar (hicbir event'te tetiklenmez sayildi): "
                    + String.join(", ", unregistered.stream().map(MethodRef::simpleName).toList()));
        }
    }

    private Set<String> registeredObservers() {
        Set<String> all = new TreeSet<>(observersForAnyName);
        observersByName.values().forEach(all::addAll);
        all.addAll(observerTypes.keySet());
        return all;
    }

    public List<Registration> registrations() {
        return List.copyOf(registrations);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public BytecodeRepository repository() {
        return repo;
    }

    public String dispatcherClass() {
        return dispatcherClass;
    }

    // ----------------------------------------------------------------------------------------------- matching

    private boolean isDispatcherMethod(MethodRef target) {
        return !target.isConstructor() && (hierarchy.isSubtypeOf(target.owner(), dispatcherClass)
                || hierarchy.isSubtypeOf(dispatcherClass, target.owner()) && !target.owner().equals("java/lang/Object"));
    }

    private boolean isEventType(Type t) {
        return t.getSort() == Type.OBJECT && hierarchy.isSubtypeOf(t.getInternalName(), eventClass);
    }

    private boolean isObserverType(Type t) {
        return t.getSort() == Type.OBJECT && hierarchy.isSubtypeOf(t.getInternalName(), observerClass);
    }

    private static boolean isNameType(Type t) {
        return t.getDescriptor().equals("Ljava/lang/String;") || t.getDescriptor().equals("[Ljava/lang/String;")
                || t.getSort() == Type.OBJECT && t.getInternalName().startsWith("java/util/");
    }

    private boolean isRegisterCall(MethodRef target) {
        if (!isDispatcherMethod(target)) {
            return false;
        }
        String n = target.name().toLowerCase();
        if (n.startsWith("un") || n.startsWith("remove") || n.startsWith("delete") || n.startsWith("detach")) {
            return false;
        }
        for (Type t : Type.getArgumentTypes(target.desc())) {
            if (isObserverType(t)) {
                return true;
            }
        }
        return false;
    }

    /** {@code dispatcher.dispatch(event)}: a dispatcher method taking an event and no observer. */
    public boolean isDispatchCall(CallSite site) {
        if (!isDispatcherMethod(site.target())) {
            return false;
        }
        boolean hasEvent = false;
        for (Type t : Type.getArgumentTypes(site.target().desc())) {
            if (isObserverType(t)) {
                return false;
            }
            hasEvent |= isEventType(t);
        }
        return hasEvent;
    }

    // ------------------------------------------------------------------------------------------------ registry

    private void buildRegistry() {
        CallerLookup noCallers = callee -> List.of();
        ValueResolver observerResolver =
                new ValueResolver(hierarchy, repo, t -> hierarchy.isSubtypeOf(t, observerClass), noCallers);
        ValueResolver nameResolver = new ValueResolver(hierarchy, repo, "java/lang/String"::equals, noCallers);
        for (String cls : new TreeSet<>(hierarchy.classes().keySet())) {
            for (MethodInfo m : hierarchy.get(cls).allMethods()) {
                if (m.calls().stream().noneMatch(cs -> isRegisterCall(cs.target()))) {
                    continue;
                }
                MethodDataFlow flow = repo.flow(m.ref()).orElse(null);
                if (flow == null) {
                    continue;
                }
                for (AbstractInsnNode insn : flow.instructions()) {
                    if (insn instanceof MethodInsnNode call
                            && isRegisterCall(new MethodRef(call.owner, call.name, call.desc))) {
                        register(flow, call, observerResolver, nameResolver);
                    }
                }
            }
        }
    }

    private void register(MethodDataFlow flow, MethodInsnNode call, ValueResolver observerResolver,
                          ValueResolver nameResolver) {
        List<TrackedValue> args = flow.arguments(call);
        Type[] types = Type.getArgumentTypes(call.desc);
        if (args.size() != types.length) {
            return; // unreachable code
        }
        int line = flow.lineOf(call);
        Set<String> observers = new TreeSet<>();
        for (int i = 0; i < types.length; i++) {
            if (!isObserverType(types[i])) {
                continue;
            }
            for (Resolved r : observerResolver.resolve(flow, args.get(i))) {
                if (r.concreteClass() != null) {
                    observers.add(r.concreteClass());
                } else if (!r.declaredType().equals(observerClass)) {
                    // e.g. "this" inside an abstract base observer: every concrete subclass registers
                    hierarchy.allSubtypes(r.declaredType()).stream()
                            .filter(s -> hierarchy.get(s) != null && hierarchy.get(s).isConcrete())
                            .forEach(observers::add);
                }
            }
        }
        if (observers.isEmpty()) {
            warnings.add("Kaydedilen observer belirlenemedi: " + flow.method().toShortString() + " ("
                    + flow.sourceFile() + ":" + line + ")");
            return;
        }
        boolean hasName = false;
        for (Type t : types) {
            hasName |= isNameType(t);
        }
        for (String observer : observers) {
            if (!hasName) {
                // addObserver(this): the dispatcher keys the observer by the event type it handles
                String type = observedType(observer);
                observerTypes.put(observer, type);
                registrations.add(new Registration(null, type, observer, flow.method(), flow.sourceFile(), line));
                continue;
            }
            Set<String> names = new TreeSet<>();
            boolean unknown = false;
            for (int i = 0; i < types.length; i++) {
                if (isNameType(types[i])) {
                    Set<String> found = namesFor(flow, args.get(i), observer, nameResolver);
                    if (found.isEmpty()) {
                        unknown = true;
                    }
                    names.addAll(found);
                }
            }
            for (String name : names) {
                observersByName.computeIfAbsent(name, k -> new TreeSet<>()).add(observer);
                registrations.add(new Registration(name, null, observer, flow.method(), flow.sourceFile(), line));
            }
            if (unknown) {
                observersForAnyName.add(observer);
                registrations.add(new Registration(null, null, observer, flow.method(), flow.sourceFile(), line));
                warnings.add("Event adi bulunamadi, " + MethodRef.simpleName(observer)
                        + " handle parametresinin kabul ettigi her event'te tetiklenebilir sayildi: "
                        + flow.method().toShortString() + " (" + flow.sourceFile() + ":" + line + ")");
            }
        }
    }

    /**
     * The event type an observer handles: the parameter of its {@code handle} method if it is more specific than the
     * base event class, otherwise the type argument in the generic signature of the class or one of its superclasses
     * ({@code class AHandler extends BaseHandler<AEvent>}), otherwise the base event class itself.
     */
    String observedType(String observer) {
        String param = handleOf(observer, Set.of())
                .map(m -> Type.getArgumentTypes(m.ref().desc())[0].getInternalName()).orElse(null);
        if (param != null && !param.equals(eventClass) && hierarchy.isSubtypeOf(param, eventClass)) {
            return param;
        }
        for (String c = observer; c != null && hierarchy.contains(c); c = hierarchy.get(c).superName()) {
            String signature = repo.classNode(c).map(cn -> cn.signature).orElse(null);
            if (signature == null) {
                continue;
            }
            java.util.regex.Matcher m = GENERIC_SUPERTYPE.matcher(signature);
            while (m.find()) {
                if (hierarchy.isSubtypeOf(m.group(1), observerClass) && hierarchy.isSubtypeOf(m.group(2), eventClass)) {
                    return m.group(2);
                }
            }
        }
        return eventClass;
    }

    /** {@code Lcom/x/IObserver<Lcom/x/AEvent;>} in a class signature. */
    private static final java.util.regex.Pattern GENERIC_SUPERTYPE =
            java.util.regex.Pattern.compile("L([\\w/$]+)<L([\\w/$]+);>");

    /**
     * Constant names a register argument can have. A call on {@code this} (e.g. {@code getEventName()} in an abstract
     * base observer) is resolved against the given observer class, so each subclass gets its own name.
     */
    private Set<String> namesFor(MethodDataFlow flow, TrackedValue value, String observer, ValueResolver resolver) {
        Set<String> names = new TreeSet<>();
        for (ValueSource src : value.sources()) {
            if (src instanceof CallResult c && callOnThis(flow, c)) {
                hierarchy.selectImplementation(observer, c.method().name(), c.method().desc())
                        .flatMap(impl -> repo.flow(impl.ref()))
                        .ifPresent(impl -> impl.returnValues().forEach(rv -> constants(resolver.resolve(impl, rv), names)));
            } else {
                constants(resolver.resolve(flow, single(src)), names);
            }
        }
        return names;
    }

    private static boolean callOnThis(MethodDataFlow flow, CallResult c) {
        AbstractInsnNode insn = flow.insn(c.insn());
        TrackedValue receiver = insn instanceof MethodInsnNode m ? flow.receiver(m) : null;
        return receiver != null && receiver.sources().stream().anyMatch(s -> s instanceof This);
    }

    // ----------------------------------------------------------------------------------------------- routing

    /** Selects the observers a dispatch call in {@code caller} continues into. */
    public EventDispatch route(MethodRef caller, CallSite site, CallerLookup callers) {
        MethodDataFlow flow = repo.flow(caller).orElse(null);
        Set<String> eventClasses = new TreeSet<>();
        Set<String> names = new TreeSet<>();
        boolean unknownEvent = flow == null;
        if (flow != null) {
            ValueResolver eventResolver =
                    new ValueResolver(hierarchy, repo, t -> hierarchy.isSubtypeOf(t, eventClass), callers);
            ValueResolver nameResolver = new ValueResolver(hierarchy, repo, "java/lang/String"::equals, callers);
            for (MethodInsnNode call : BytecodeRepository.callInsns(flow, site.target(), site.line())) {
                List<TrackedValue> args = flow.arguments(call);
                Type[] types = Type.getArgumentTypes(call.desc);
                for (int i = 0; i < Math.min(types.length, args.size()); i++) {
                    if (types[i].getDescriptor().equals("Ljava/lang/String;")) {
                        constants(nameResolver.resolve(flow, args.get(i)), names); // dispatch("CAR_RENTED", event)
                    } else if (isEventType(types[i])) {
                        unknownEvent |= describeEvent(flow, args.get(i), eventResolver, nameResolver,
                                eventClasses, names);
                    }
                }
            }
        }

        Set<String> registeredNames = new TreeSet<>(names);
        registeredNames.retainAll(observersByName.keySet());
        boolean registryEmpty = registrations.isEmpty();
        Set<String> observers = new TreeSet<>();
        Resolution resolution;
        if (eventClasses.isEmpty() || unknownEvent && registeredNames.isEmpty()) {
            // Nothing (reliable) is known about the event: every observer that is registered at all.
            resolution = Resolution.ALL;
            observers.addAll(registryEmpty ? concreteObservers() : registeredObservers());
        } else {
            resolution = registeredNames.isEmpty() ? Resolution.BY_TYPE : Resolution.BY_NAME;
            if (!names.isEmpty()) {
                registeredNames.forEach(n -> observers.addAll(observersByName.get(n)));
            } else {
                observersByName.values().forEach(observers::addAll); // name unknown: the type check below decides
            }
            observerTypes.forEach((observer, type) -> {
                if (eventClasses.stream().anyMatch(e -> typeMatches(e, type))) {
                    observers.add(observer);
                }
            });
            observers.addAll(observersForAnyName);
            if (registryEmpty) {
                observers.addAll(concreteObservers());
            }
            // An observer whose handle(T) cannot take the event would fail with a ClassCastException: never selected.
            observers.removeIf(o -> eventClasses.stream().noneMatch(e -> handleAccepts(o, e)));
        }

        List<DispatchTarget> targets = new ArrayList<>();
        for (String o : observers) {
            handleOf(o, eventClasses).ifPresent(m -> targets.add(new DispatchTarget(o, m.ref())));
        }
        ClassInfo callerClass = hierarchy.get(caller.owner());
        return new EventDispatch(caller, callerClass == null ? null : callerClass.sourceFile(), site.line(),
                site.target(), List.copyOf(eventClasses),
                List.copyOf(resolution == Resolution.BY_NAME ? registeredNames : names), resolution,
                List.copyOf(targets));
    }

    /** Adds the classes and names an event argument can have; returns {@code true} if its class is unknown. */
    private boolean describeEvent(MethodDataFlow flow, TrackedValue event, ValueResolver eventResolver,
                                  ValueResolver nameResolver, Set<String> classes, Set<String> names) {
        boolean unknown = false;
        for (ValueSource src : event.sources()) {
            if (src instanceof NewObject n) {
                // new CarRentedEvent(...) right here: the constructor arguments are known exactly
                classes.add(n.type());
                names.addAll(namesFromNewSite(flow, n, nameResolver));
                names.addAll(constantGetterNames(n.type()));
                continue;
            }
            for (Resolved r : eventResolver.resolve(flow, single(src))) {
                if (r.concreteClass() != null) {
                    classes.add(r.concreteClass());
                    names.addAll(namesOfClass(r.concreteClass(), nameResolver));
                } else {
                    unknown = true;
                }
            }
        }
        return unknown;
    }

    private Set<String> namesFromNewSite(MethodDataFlow flow, NewObject n, ValueResolver nameResolver) {
        Set<String> names = new TreeSet<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (insn instanceof MethodInsnNode init && init.getOpcode() == Opcodes.INVOKESPECIAL
                    && init.name.equals("<init>") && init.owner.equals(n.type())) {
                TrackedValue receiver = flow.receiver(init);
                if (receiver == null || !receiver.sources().contains(n)) {
                    continue;
                }
                List<TrackedValue> actuals = flow.arguments(init);
                repo.flow(new MethodRef(init.owner, init.name, init.desc))
                        .ifPresent(ctor -> names.addAll(namesFromConstructor(ctor, flow, actuals, nameResolver)));
            }
        }
        return names;
    }

    /** Names of an event class: from its constructors' {@code super(...)} calls and constant {@code getXxx()}s. */
    private Set<String> namesOfClass(String cls, ValueResolver nameResolver) {
        Set<String> names = new TreeSet<>();
        ClassNode cn = repo.classNode(cls).orElse(null);
        if (cn == null) {
            return names;
        }
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<init>")) {
                repo.flow(new MethodRef(cls, m.name, m.desc))
                        .ifPresent(ctor -> names.addAll(namesFromConstructor(ctor, null, null, nameResolver)));
            }
        }
        names.addAll(constantGetterNames(cls));
        return names;
    }

    /**
     * String arguments a constructor passes to {@code super(...)}/{@code this(...)}. A constructor parameter is
     * replaced by the actual argument when the {@code new} site is known ({@code actualsFlow}/{@code actuals}).
     */
    private Set<String> namesFromConstructor(MethodDataFlow ctor, MethodDataFlow actualsFlow, List<TrackedValue> actuals,
                                             ValueResolver nameResolver) {
        return namesFromConstructor(ctor, actualsFlow, actuals, nameResolver, 0);
    }

    private Set<String> namesFromConstructor(MethodDataFlow ctor, MethodDataFlow actualsFlow, List<TrackedValue> actuals,
                                             ValueResolver nameResolver, int depth) {
        Set<String> names = new TreeSet<>();
        for (AbstractInsnNode insn : ctor.instructions()) {
            if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !call.name.equals("<init>")) {
                continue;
            }
            TrackedValue receiver = ctor.receiver(call);
            if (receiver == null || receiver.sources().stream().noneMatch(s -> s instanceof This)) {
                continue; // not super(...) / this(...)
            }
            Type[] types = Type.getArgumentTypes(call.desc);
            List<TrackedValue> args = ctor.arguments(call);
            Set<String> here = new TreeSet<>();
            for (int i = 0; i < Math.min(types.length, args.size()); i++) {
                if (!types[i].getDescriptor().equals("Ljava/lang/String;")) {
                    continue;
                }
                for (ValueSource src : args.get(i).sources()) {
                    int param = src instanceof Parameter p ? ctor.parameterIndex(p.local()) : -1;
                    if (param >= 0 && actuals != null && param < actuals.size()) {
                        constants(nameResolver.resolve(actualsFlow, actuals.get(param)), here);
                    } else if (param < 0 || actuals == null) {
                        constants(nameResolver.resolve(ctor, single(src)), here);
                    }
                }
            }
            if (here.isEmpty() && depth < 5 && !call.owner.equals("java/lang/Object")) {
                // e.g. UrgentTyreChangedEvent() -> TyreChangedEvent() -> super("TYRE_CHANGED"): follow the chain
                MethodDataFlow callee = repo.flow(new MethodRef(call.owner, call.name, call.desc)).orElse(null);
                if (callee != null) {
                    here.addAll(namesFromConstructor(callee, ctor, args, nameResolver, depth + 1));
                }
            }
            names.addAll(here);
        }
        return names;
    }

    /** {@code String getName() { return "PAYMENT_DONE"; }} style overrides in the class or its superclasses. */
    private Set<String> constantGetterNames(String cls) {
        Set<String> names = new TreeSet<>();
        for (String c = cls; c != null && hierarchy.contains(c); c = hierarchy.get(c).superName()) {
            for (MethodInfo m : hierarchy.get(c).allMethods()) {
                if (m.isStatic() || m.isAbstract() || !m.ref().desc().equals("()Ljava/lang/String;")) {
                    continue;
                }
                repo.flow(m.ref()).ifPresent(flow -> flow.returnValues().forEach(rv -> rv.sources().forEach(src -> {
                    if (src instanceof Constant k && k.value() instanceof String s) {
                        names.add(s);
                    } else if (src instanceof FieldValue f) {
                        repo.field(f.owner(), f.name())
                                .filter(fn -> fn.value instanceof String)
                                .ifPresent(fn -> names.add((String) fn.value));
                    }
                })));
            }
        }
        return names;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private boolean typeMatches(String event, String observedType) {
        if (observedType.equals(eventClass)) {
            return true; // IObserver<Event>: receives every event
        }
        return exactTypeMatch ? event.equals(observedType) : hierarchy.isSubtypeOf(event, observedType);
    }

    private boolean handleAccepts(String observer, String event) {
        return handleOf(observer, Set.of(event))
                .map(m -> hierarchy.isSubtypeOf(event, Type.getArgumentTypes(m.ref().desc())[0].getInternalName()))
                .orElse(false);
    }

    private Set<String> concreteObservers() {
        Set<String> result = new TreeSet<>();
        for (String s : hierarchy.allSubtypes(observerClass)) {
            ClassInfo c = hierarchy.get(s);
            if (c != null && c.isConcrete()) {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * The {@code handle} method that runs for an observer: the most specific non-bridge one-argument implementation
     * (for {@code IObserver<CarRentedEvent>} that is {@code handle(CarRentedEvent)}, not the generated bridge).
     */
    private Optional<MethodInfo> handleOf(String observer, Set<String> eventClasses) {
        List<MethodInfo> candidates = new ArrayList<>();
        List<MethodInfo> bridges = new ArrayList<>();
        for (String c = observer; c != null && hierarchy.contains(c); c = hierarchy.get(c).superName()) {
            for (MethodInfo m : hierarchy.get(c).allMethods()) {
                if (!m.ref().name().equals(handleMethod) || m.isStatic() || m.isAbstract()
                        || Type.getArgumentTypes(m.ref().desc()).length != 1) {
                    continue;
                }
                ((m.access() & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0 ? bridges : candidates).add(m);
            }
        }
        if (candidates.isEmpty()) {
            return bridges.stream().findFirst();
        }
        if (candidates.size() > 1 && !eventClasses.isEmpty()) {
            for (MethodInfo m : candidates) {
                String param = Type.getArgumentTypes(m.ref().desc())[0].getInternalName();
                if (eventClasses.stream().anyMatch(e -> hierarchy.isSubtypeOf(e, param))) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.of(candidates.get(0));
    }

    private static void constants(Set<Resolved> resolved, Set<String> out) {
        for (Resolved r : resolved) {
            if (r.constant() instanceof String s) {
                out.add(s);
            }
        }
    }

    private static TrackedValue single(ValueSource src) {
        return new TrackedValue(BasicValue.REFERENCE_VALUE, Set.of(src));
    }
}
