package callanalyzer.analysis;

import callanalyzer.model.MethodRef;

/**
 * One frame of a call chain.
 *
 * @param method   the method entered
 * @param callLine the line in the previous frame's method that made the call, {@code -1} for the entry method
 */
public record ChainStep(MethodRef method, int callLine) {
}
