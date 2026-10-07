package callanalyzer.model;

import org.objectweb.asm.Opcodes;

import java.util.List;

/** A method declared in a loaded class, together with every call site in its body (in bytecode order). */
public record MethodInfo(MethodRef ref, int access, List<CallSite> calls) {

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

    public boolean hasBody() {
        return !isAbstract() && !isNative();
    }
}
