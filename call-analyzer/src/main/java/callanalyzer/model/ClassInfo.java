package callanalyzer.model;

import org.objectweb.asm.Opcodes;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Structural information about a loaded class or interface.
 *
 * @param name       JVM internal name, e.g. {@code com/example/Bmw}
 * @param superName  internal name of the superclass, {@code null} for {@code java/lang/Object}
 * @param interfaces directly implemented / extended interfaces
 * @param methods    declared methods keyed by {@code name + descriptor}
 * @param sourceFile source file name from debug info, may be {@code null}
 */
public record ClassInfo(String name, int access, String superName, List<String> interfaces,
                        Map<String, MethodInfo> methods, String sourceFile) {

    public boolean isInterface() {
        return (access & Opcodes.ACC_INTERFACE) != 0;
    }

    public boolean isAbstract() {
        return (access & Opcodes.ACC_ABSTRACT) != 0;
    }

    public boolean isFinal() {
        return (access & Opcodes.ACC_FINAL) != 0;
    }

    /** {@code true} for classes that can be instantiated and therefore act as receivers at runtime. */
    public boolean isConcrete() {
        return !isInterface() && !isAbstract();
    }

    public MethodInfo method(String name, String desc) {
        return methods.get(name + desc);
    }

    public Collection<MethodInfo> allMethods() {
        return methods.values();
    }
}
