package callanalyzer.acquire;

import callanalyzer.analysis.Finding;

import java.util.List;

/**
 * How a target implementation is used in the flow.
 *
 * @param className internal name of the implementation class
 * @param certain   calls whose only possible receiver is this class (e.g. {@code bmw.start()} on a {@code Bmw} field)
 * @param possible  calls where this class is one of several possible receivers (e.g. {@code car.start()} on {@code ICar})
 */
public record ClassUsage(String className, List<Finding> certain, List<Finding> possible) {
}
