package callanalyzer.analysis;

import callanalyzer.model.MethodRef;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outcome of an analysis run.
 *
 * @param entries   resolved entry methods
 * @param targets   resolved target types (internal names)
 * @param findings  calls reaching target implementations, in discovery (breadth-first) order
 * @param edges     outgoing calls of every analysed method
 * @param depths    call depth of every analysed method
 * @param hierarchy the class hierarchy the analysis ran on
 * @param warnings  non-fatal problems (e.g. a target type that is not on the class path)
 */
public record AnalysisResult(List<MethodRef> entries, List<String> targets, List<Finding> findings,
                             Map<MethodRef, List<CallEdge>> edges, Map<MethodRef, Integer> depths,
                             ClassHierarchy hierarchy, List<String> warnings) {

    /** Methods whose bodies were analysed. */
    public Set<MethodRef> analysedMethods() {
        return edges.keySet();
    }
}
