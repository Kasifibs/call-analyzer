package callanalyzer.analysis;

import callanalyzer.model.MethodRef;

/**
 * One frame of a call chain.
 *
 * @param method   the method entered
 * @param callLine the line in the previous frame's method that made the call, {@code -1} for the entry method
 * @param via      how the method was reached when it was not a plain call, e.g. {@code event CAR_RENTED}; may be null
 * @param assumed  {@code true} when this event hop rests on an assumption: an event created with {@code new} whose
 *                 publication no dispatcher call confirms, or a dispatch whose event could not be determined
 */
public record ChainStep(MethodRef method, int callLine, String via, boolean assumed) {
}
