package callanalyzer.analysis;

import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.model.CallSite;
import callanalyzer.model.MethodRef;

import java.util.List;

/**
 * A call that may reach a class implementing (or extending) the target type.
 *
 * @param targetType          the target type that matched, internal name (e.g. {@code com/example/ICar})
 * @param caller              method containing the call
 * @param callerSourceFile    source file of the caller, may be {@code null}
 * @param site                the call itself
 * @param depth               call depth of {@code caller} (entry method = 0)
 * @param matchedTargets      receiver classes that implement the target type and the method that would run on each
 * @param receiverTypeMatches {@code true} when the static receiver type already implements the target type
 *                            (e.g. {@code ICar car; car.start()}); {@code false} when the match is only possible through
 *                            dynamic dispatch on a broader type (e.g. {@code Object o; o.toString()})
 * @param interfaceMethod     {@code true} when the called method is declared by the target type
 * @param internalCall        {@code true} when the caller itself belongs to a class implementing the target type
 * @param chain               shortest call chain from an entry method to {@code caller} (inclusive)
 */
public record Finding(String targetType, MethodRef caller, String callerSourceFile, CallSite site, int depth,
                      List<DispatchTarget> matchedTargets, boolean receiverTypeMatches, boolean interfaceMethod,
                      boolean internalCall, List<ChainStep> chain) {

    /** Reached only through an event hop that rests on an assumption (see {@link ChainStep#assumed()}). */
    public boolean viaAssumedEvent() {
        return chain.stream().anyMatch(ChainStep::assumed);
    }
}
