package callanalyzer.acquire;

import callanalyzer.model.MethodRef;

import java.util.List;

/**
 * Whether an analysed entry method carries the acquire annotation (e.g. {@code @AcquireCar(cars = {Opel.class})}),
 * and the classes listed in it.
 *
 * @param method     the entry method
 * @param sourceFile source file of the method, may be {@code null}
 * @param line       first line of the method body, {@code -1} if unknown
 * @param present    {@code true} when the annotation is on the method
 * @param cars       the classes given in the annotation
 */
public record AnnotationCheck(MethodRef method, String sourceFile, int line, boolean present, List<AcquiredCar> cars) {
}
