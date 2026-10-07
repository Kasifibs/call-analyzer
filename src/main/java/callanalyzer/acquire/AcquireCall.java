package callanalyzer.acquire;

import callanalyzer.model.MethodRef;

import java.util.List;

/** A call of the acquirer method in the analysed flow, with the elements of the list it receives. */
public record AcquireCall(MethodRef caller, String sourceFile, int line, MethodRef called, List<AcquiredCar> cars) {
}
