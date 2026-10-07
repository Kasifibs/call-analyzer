package callanalyzer.model;

/**
 * A single method invocation found in a method body.
 *
 * @param kind      how the call is dispatched
 * @param target    the statically referenced method (the owner is the compile-time type of the receiver)
 * @param line      source line number, or {@code -1} when the class has no debug info
 * @param viaLambda {@code true} when the call comes from a lambda or method reference
 *                  ({@code invokedynamic} + {@code LambdaMetafactory}) rather than a plain invoke instruction
 */
public record CallSite(InvokeKind kind, MethodRef target, int line, boolean viaLambda) {
}
