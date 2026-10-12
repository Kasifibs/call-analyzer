package callanalyzer.model;

import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * A method declared in a loaded class, together with every call site in its body (in bytecode order) and the
 * descriptors of its annotations (e.g. {@code Lcom/example/AcquireCar;}, runtime and class retention).
 */
public record MethodInfo(MethodRef ref, int access, List<CallSite> calls, List<String> annotations) {

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public boolean isNative() {
        return (access & Opcodes.ACC_NATIVE) != 0;
    }

    public boolean isStatic() {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public boolean isPrivate() {
        return (access & Opcodes.ACC_PRIVATE) != 0;
    }

    public boolean isFinal() {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    public boolean isSynthetic() {
        return (access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0;
    }

    public boolean hasBody() {
        return !isAbstract() && !isNative();
    }
}
