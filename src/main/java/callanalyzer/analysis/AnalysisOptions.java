package callanalyzer.analysis;

import java.util.ArrayList;
import java.util.List;

/** Settings for a {@link CallGraphAnalyzer} run. */
public final class AnalysisOptions {

    private final List<String> entries = new ArrayList<>();
    private final List<String> targets = new ArrayList<>();
    private final List<String> includePrefixes = new ArrayList<>();
    private final List<String> excludePrefixes = new ArrayList<>();
    private int maxDepth = -1;
    private boolean includeConstructors;
    private boolean onlyInterfaceMethods;
    private boolean skipInternalCalls;
    private boolean directOnly;

    /** Entry method selector, see {@link MethodSpec}. */
    public AnalysisOptions entry(String spec) {
        entries.add(spec);
        return this;
    }

    /** Interface (or class) whose implementations / subclasses are searched for, e.g. {@code com.example.ICar}. */
    public AnalysisOptions target(String className) {
        targets.add(className);
        return this;
    }

    /** Only descend into classes whose name starts with one of these prefixes (default: every loaded class). */
    public AnalysisOptions include(String classPrefix) {
        includePrefixes.add(toInternal(classPrefix));
        return this;
    }

    /** Never descend into classes whose name starts with one of these prefixes. */
    public AnalysisOptions exclude(String classPrefix) {
        excludePrefixes.add(toInternal(classPrefix));
        return this;
    }

    /** Maximum call depth to descend (entry = 0); negative means unlimited. */
    public AnalysisOptions maxDepth(int maxDepth) {
        this.maxDepth = maxDepth;
        return this;
    }

    /** Also report {@code new Bmw()} style constructor calls on target types. */
    public AnalysisOptions includeConstructors(boolean value) {
        this.includeConstructors = value;
        return this;
    }

    /** Only report methods that are declared by the target type (e.g. methods of {@code ICar} itself). */
    public AnalysisOptions onlyInterfaceMethods(boolean value) {
        this.onlyInterfaceMethods = value;
        return this;
    }

    /** Do not report calls made from inside a target implementation to a target implementation. */
    public AnalysisOptions skipInternalCalls(boolean value) {
        this.skipInternalCalls = value;
        return this;
    }

    /**
     * Only report calls whose static receiver type already is a target subtype; drops matches that are only
     * possible through dynamic dispatch on a broader type (e.g. {@code Object#toString()}).
     */
    public AnalysisOptions directOnly(boolean value) {
        this.directOnly = value;
        return this;
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }

    public List<String> targets() {
        return List.copyOf(targets);
    }

    public List<String> includePrefixes() {
        return List.copyOf(includePrefixes);
    }

    public List<String> excludePrefixes() {
        return List.copyOf(excludePrefixes);
    }

    public int maxDepth() {
        return maxDepth;
    }

    public boolean includeConstructors() {
        return includeConstructors;
    }

    public boolean onlyInterfaceMethods() {
        return onlyInterfaceMethods;
    }

    public boolean skipInternalCalls() {
        return skipInternalCalls;
    }

    public boolean directOnly() {
        return directOnly;
    }

    private static String toInternal(String name) {
        return name.trim().replace('.', '/');
    }
}
