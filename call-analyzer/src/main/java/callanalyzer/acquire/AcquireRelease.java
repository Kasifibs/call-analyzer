package callanalyzer.acquire;

import callanalyzer.model.MethodRef;

/**
 * Whether one element acquired by an acquirer call is released again in the same method.
 *
 * @param caller      method making the acquire call
 * @param acquireLine line of the acquire call
 * @param car         the acquired element
 * @param status      outcome
 * @param release     the release call that releases it ({@code null} if none does)
 */
public record AcquireRelease(MethodRef caller, String sourceFile, int acquireLine, AcquiredCar car, Status status,
                             ReleaseCall release) {

    public enum Status {
        /** Released in a {@code finally} block after the acquire. */
        RELEASED,
        /** Released after the acquire, but not in a {@code finally} block: not released if an exception is thrown. */
        NOT_IN_FINALLY,
        /** A release list has an element whose class could not be determined; it may be this one. */
        UNDECIDED,
        /** No release call in the method releases it. */
        UNRELEASED
    }
}
