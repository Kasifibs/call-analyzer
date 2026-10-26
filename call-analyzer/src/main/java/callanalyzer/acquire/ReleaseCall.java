package callanalyzer.acquire;

import callanalyzer.model.MethodRef;

import java.util.List;

/**
 * A call of the release method in a method that acquires.
 *
 * @param inFinally whether (a copy of) the call is in a {@code finally} block
 * @param cars      elements of the list it receives
 */
public record ReleaseCall(MethodRef caller, String sourceFile, int line, boolean inFinally, List<AcquiredCar> cars) {
}
