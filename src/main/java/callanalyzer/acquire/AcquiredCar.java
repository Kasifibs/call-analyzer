package callanalyzer.acquire;

/**
 * One element found in a list passed to the acquirer method.
 *
 * @param concreteClass the class of the element (internal name), or {@code null} when it cannot be determined
 *                      statically (e.g. a field typed as the interface itself)
 * @param declaredType  the static type the element was found with (internal name)
 * @param origin        human readable description of where the element comes from
 */
public record AcquiredCar(String concreteClass, String declaredType, String origin) {

    public boolean isResolved() {
        return concreteClass != null;
    }
}
