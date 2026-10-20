package callanalyzer.analysis;

import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Type hierarchy over the loaded classes plus method resolution.
 *
 * <p>Virtual and interface calls are resolved with Class Hierarchy Analysis (CHA): a call on static type
 * {@code T} may reach the implementation chosen by every concrete, loaded subtype of {@code T}. This is
 * deliberately conservative (it does not depend on where objects are created, so it also covers objects
 * built by frameworks such as Spring via reflection).
 */
public final class ClassHierarchy {

    /** A possible runtime target of a call: the receiver's concrete class and the method that would run. */
    public record DispatchTarget(String receiverClass, MethodRef method) {
    }

    private final Map<String, ClassInfo> classes;
    private final Map<String, Set<String>> directSubtypes = new HashMap<>();
    private final Map<String, Set<String>> supertypeCache = new HashMap<>();
    private final Map<String, Set<String>> subtypeCache = new HashMap<>();

    public ClassHierarchy(Map<String, ClassInfo> classes) {
        this.classes = Map.copyOf(classes);
        for (ClassInfo c : classes.values()) {
            if (c.superName() != null) {
                directSubtypes.computeIfAbsent(c.superName(), k -> new TreeSet<>()).add(c.name());
            }
            for (String i : c.interfaces()) {
                directSubtypes.computeIfAbsent(i, k -> new TreeSet<>()).add(c.name());
            }
        }
    }

    public ClassInfo get(String internalName) {
        return classes.get(internalName);
    }

    public boolean contains(String internalName) {
        return classes.containsKey(internalName);
    }

    public Map<String, ClassInfo> classes() {
        return classes;
    }

    public Optional<MethodInfo> method(MethodRef ref) {
        ClassInfo c = classes.get(ref.owner());
        return Optional.ofNullable(c == null ? null : c.method(ref.name(), ref.desc()));
    }

    /**
     * Converts a user supplied class name ({@code com.example.Outer.Inner}, {@code com.example.Outer$Inner}
     * or {@code com/example/Outer$Inner}) into the internal name of a loaded class. Falls back to a plain
     * dot-to-slash conversion if no loaded class matches.
     */
    public String resolveClassName(String userName) {
        String internal = userName.trim().replace('.', '/');
        if (classes.containsKey(internal)) {
            return internal;
        }
        // Try turning trailing package separators into nested class separators: a/b/Outer/Inner -> a/b/Outer$Inner
        String candidate = internal;
        int slash;
        while ((slash = candidate.lastIndexOf('/')) > 0) {
            candidate = candidate.substring(0, slash) + "$" + candidate.substring(slash + 1);
            if (classes.containsKey(candidate)) {
                return candidate;
            }
        }
        return internal;
    }

    /** All supertypes of {@code type} (classes and interfaces), including the type itself. */
    public Set<String> allSupertypes(String type) {
        Set<String> cached = supertypeCache.get(type);
        if (cached != null) {
            return cached;
        }
        Set<String> result = new LinkedHashSet<>();
        Deque<String> work = new ArrayDeque<>();
        work.add(type);
        while (!work.isEmpty()) {
            String t = work.poll();
            if (!result.add(t)) {
                continue;
            }
            ClassInfo c = classes.get(t);
            if (c != null) {
                if (c.superName() != null) {
                    work.add(c.superName());
                }
                work.addAll(c.interfaces());
            }
        }
        Set<String> frozen = Collections.unmodifiableSet(result);
        supertypeCache.put(type, frozen);
        return frozen;
    }

    public boolean isSubtypeOf(String type, String superType) {
        return allSupertypes(type).contains(superType);
    }

    /** All loaded subtypes of {@code type} (transitively), including the type itself. */
    public Set<String> allSubtypes(String type) {
        Set<String> cached = subtypeCache.get(type);
        if (cached != null) {
            return cached;
        }
        Set<String> result = new TreeSet<>();
        Deque<String> work = new ArrayDeque<>();
        work.add(type);
        while (!work.isEmpty()) {
            String t = work.poll();
            if (result.add(t)) {
                work.addAll(directSubtypes.getOrDefault(t, Set.of()));
            }
        }
        Set<String> frozen = Collections.unmodifiableSet(result);
        subtypeCache.put(type, frozen);
        return frozen;
    }

    /**
     * Finds the method a symbolic reference resolves to (JVM method resolution): the owner, then its
     * superclasses, then its superinterfaces. Abstract declarations are returned as well.
     */
    public Optional<MethodInfo> resolveDeclared(MethodRef ref) {
        for (String c = ref.owner(); c != null; ) {
            ClassInfo info = classes.get(c);
            if (info == null) {
                break;
            }
            MethodInfo m = info.method(ref.name(), ref.desc());
            if (m != null) {
                return Optional.of(m);
            }
            c = info.superName();
        }
        for (String t : allSupertypes(ref.owner())) {
            ClassInfo info = classes.get(t);
            if (info != null && info.isInterface()) {
                MethodInfo m = info.method(ref.name(), ref.desc());
                if (m != null && !m.isStatic()) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Selects the method that runs when {@code name+desc} is invoked on an instance whose runtime class is
     * {@code concreteClass}: the nearest non-abstract declaration in the superclass chain, otherwise a
     * default method from one of its interfaces.
     */
    public Optional<MethodInfo> selectImplementation(String concreteClass, String name, String desc) {
        for (String c = concreteClass; c != null; ) {
            ClassInfo info = classes.get(c);
            if (info == null) {
                break;
            }
            MethodInfo m = info.method(name, desc);
            if (m != null && !m.isStatic() && !m.isAbstract()) {
                return Optional.of(m);
            }
            c = info.superName();
        }
        for (String t : allSupertypes(concreteClass)) {
            ClassInfo info = classes.get(t);
            if (info != null && info.isInterface()) {
                MethodInfo m = info.method(name, desc);
                if (m != null && !m.isStatic() && !m.isAbstract()) {
                    return Optional.of(m);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Every method a call site may execute. Returns an empty list when nothing can be resolved inside the
     * loaded classes (for example a call into the JDK or an unloaded library).
     */
    public List<DispatchTarget> dispatch(CallSite site) {
        MethodRef ref = site.target();
        Optional<MethodInfo> declared = resolveDeclared(ref);
        if (!site.kind().isDynamicDispatch()) {
            return declared.map(m -> List.of(new DispatchTarget(ref.owner(), m.ref()))).orElse(List.of());
        }
        if (declared.isPresent()) {
            MethodInfo m = declared.get();
            ClassInfo declaring = classes.get(m.ref().owner());
            boolean cannotBeOverridden = m.isPrivate() || m.isFinal()
                    || (declaring != null && declaring.isFinal() && !declaring.isInterface());
            if (cannotBeOverridden && m.hasBody()) {
                return List.of(new DispatchTarget(ref.owner(), m.ref()));
            }
        }
        Set<DispatchTarget> targets = new LinkedHashSet<>();
        for (String sub : allSubtypes(ref.owner())) {
            ClassInfo info = classes.get(sub);
            if (info == null || !info.isConcrete()) {
                continue;
            }
            selectImplementation(sub, ref.name(), ref.desc())
                    .ifPresent(m -> targets.add(new DispatchTarget(sub, m.ref())));
        }
        return List.copyOf(targets);
    }
}
