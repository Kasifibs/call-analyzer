package callanalyzer.acquire;

import java.util.List;

/**
 * Comparison of the classes acquired by the entry method (via the acquirer method and/or the acquire annotation) with
 * the classes used in the flow.
 *
 * @param acquirer        the acquirer method selector as given by the user, {@code null} if not used
 * @param annotation      the acquire annotation type (internal name), {@code null} if not used
 * @param calls           acquirer calls in the entry method(s); only these (and the annotation) count as acquired
 * @param otherCalls      acquirer calls elsewhere in the flow, reported for information but not compared
 * @param annotations     for every entry method, whether it carries the acquire annotation and its classes
 * @param acquired        resolved classes in the acquired lists (internal names, sorted)
 * @param unresolved      list elements whose class could not be determined
 * @param covered         used classes that are in the list
 * @param missing         classes used with certainty (receiver is known exactly) but not in the list
 * @param possiblyMissing classes only possibly used (through an interface call) and not in the list
 * @param undecided       classes not in the list but possibly covered by an unresolved element
 * @param exempt          classes not in the list that the entry method may use without acquiring them (configured
 *                        {@link AcquireExemption}s); never counted as missing
 * @param unused          acquired classes that are not used in the flow
 */
public record AcquireComparison(String acquirer, String annotation, List<AcquireCall> calls,
                                List<AcquireCall> otherCalls, List<AnnotationCheck> annotations, List<String> acquired,
                                List<AcquiredCar> unresolved, List<ClassUsage> covered, List<ClassUsage> missing,
                                List<ClassUsage> possiblyMissing, List<ClassUsage> undecided, List<ClassUsage> exempt,
                                List<String> unused) {
}
