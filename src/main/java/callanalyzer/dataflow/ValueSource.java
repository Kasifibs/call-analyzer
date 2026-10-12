package callanalyzer.dataflow;

import callanalyzer.model.MethodRef;

/** Where a value on the operand stack or in a local variable may have come from. */
public sealed interface ValueSource {

    /** {@code new T()} or {@code new T[n]} at instruction {@code insn}. */
    record NewObject(int insn, String type) implements ValueSource {
    }

    /** Read of a field ({@code GETFIELD}/{@code GETSTATIC}). */
    record FieldValue(String owner, String name, String desc) implements ValueSource {
    }

    /** Return value of the call at instruction {@code insn}. */
    record CallResult(int insn, MethodRef method) implements ValueSource {
    }

    /** A method parameter, identified by its local variable slot. */
    record Parameter(int local, String desc) implements ValueSource {
    }

    /** {@code this}. */
    record This() implements ValueSource {
    }

    /** A constant loaded with {@code LDC}: a {@link String} or a class literal ({@link org.objectweb.asm.Type}). */
    record Constant(Object value) implements ValueSource {
    }
}
