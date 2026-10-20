package callanalyzer.scan;

import callanalyzer.model.MethodRef;

/**
 * A method that acquires cars itself and is therefore analysed as an entry method.
 *
 * @param method        the method
 * @param callsAcquirer {@code true} if it calls the acquirer method (directly or in a lambda defined in it)
 * @param annotated     {@code true} if it carries the acquire annotation
 */
public record Candidate(MethodRef method, boolean callsAcquirer, boolean annotated) {
}
