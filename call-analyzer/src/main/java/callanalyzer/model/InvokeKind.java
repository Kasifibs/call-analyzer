package callanalyzer.model;

/** How a call site dispatches to its target, mirroring the JVM invoke instructions. */
public enum InvokeKind {
    /** {@code invokestatic}: exactly one target, no dynamic dispatch. */
    STATIC,
    /** {@code invokespecial}: constructors, {@code super.x()} and private methods; no dynamic dispatch. */
    SPECIAL,
    /** {@code invokevirtual}: dispatched on the runtime class of the receiver. */
    VIRTUAL,
    /** {@code invokeinterface}: dispatched on the runtime class of the receiver. */
    INTERFACE;

    public boolean isDynamicDispatch() {
        return this == VIRTUAL || this == INTERFACE;
    }
}
