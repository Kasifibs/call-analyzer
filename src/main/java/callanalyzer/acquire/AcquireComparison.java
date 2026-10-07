package callanalyzer.acquire;

import java.util.List;

/**
 * Comparison of the classes acquired via the acquirer method with the classes used in the flow.
 *
 * @param acquirer        the acquirer method selector as given by the user
 * @param calls           every acquirer call found in the flow
 * @param acquired        resolved classes in the acquired lists (internal names, sorted)
 * @param unresolved      list elements whose class could not be determined
 * @param covered         used classes that are in the list
 * @param missing         classes used with certainty (receiver is known exactly) but not in the list
 * @param possiblyMissing classes only possibly used (through an interface call) and not in the list
 * @param undecided       classes not in the list but possibly covered by an unresolved element
 * @param unused          acquired classes that are not used in the flow
 */
public record AcquireComparison(String acquirer, List<AcquireCall> calls, List<String> acquired,
                                List<AcquiredCar> unresolved, List<ClassUsage> covered, List<ClassUsage> missing,
                                List<ClassUsage> possiblyMissing, List<ClassUsage> undecided, List<String> unused) {
}
