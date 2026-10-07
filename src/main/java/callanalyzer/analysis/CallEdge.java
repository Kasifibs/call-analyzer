package callanalyzer.analysis;

import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.model.CallSite;
import callanalyzer.model.MethodRef;

import java.util.List;

/** A call site in {@code caller} together with every method it may execute ({@code targets}). */
public record CallEdge(MethodRef caller, CallSite site, List<DispatchTarget> targets) {
}
