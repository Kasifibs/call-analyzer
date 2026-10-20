package callanalyzer.dataflow;

import callanalyzer.model.InvokeKind;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Lazily parsed full class files ({@link ClassNode}s) and cached {@link MethodDataFlow}s. */
public final class BytecodeRepository {

    private final Map<String, byte[]> classBytes;
    private final Map<String, Optional<ClassNode>> classNodes = new HashMap<>();
    private final Map<MethodRef, Optional<MethodDataFlow>> flows = new HashMap<>();

    public BytecodeRepository(Map<String, byte[]> classBytes) {
        this.classBytes = classBytes;
    }

    public Optional<ClassNode> classNode(String internalName) {
        return classNodes.computeIfAbsent(internalName, n -> {
            byte[] bytes = classBytes.get(n);
            if (bytes == null) {
                return Optional.empty();
            }
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
            return Optional.of(cn);
        });
    }

    public Optional<MethodNode> methodNode(MethodRef ref) {
        return classNode(ref.owner()).flatMap(cn -> cn.methods.stream()
                .filter(m -> m.name.equals(ref.name()) && m.desc.equals(ref.desc()))
                .findFirst());
    }

    public Optional<FieldNode> field(String owner, String name) {
        return classNode(owner).flatMap(cn -> cn.fields.stream().filter(f -> f.name.equals(name)).findFirst());
    }

    public Optional<MethodDataFlow> flow(MethodRef ref) {
        return flows.computeIfAbsent(ref, r -> classNode(r.owner()).flatMap(cn -> cn.methods.stream()
                .filter(m -> m.name.equals(r.name()) && m.desc.equals(r.desc()))
                .findFirst()
                .flatMap(m -> MethodDataFlow.analyze(cn, m))));
    }

    public static InvokeKind kindOf(MethodInsnNode m) {
        return switch (m.getOpcode()) {
            case Opcodes.INVOKESTATIC -> InvokeKind.STATIC;
            case Opcodes.INVOKESPECIAL -> InvokeKind.SPECIAL;
            case Opcodes.INVOKEINTERFACE -> InvokeKind.INTERFACE;
            default -> InvokeKind.VIRTUAL;
        };
    }

    /**
     * {@code invokedynamic} instructions in {@code flow} creating a lambda / method reference whose implementation is
     * {@code target}, optionally only on {@code line} (ignored if negative).
     */
    public static List<InvokeDynamicInsnNode> indyInsns(MethodDataFlow flow, MethodRef target, int line) {
        List<InvokeDynamicInsnNode> found = new ArrayList<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs.length >= 2
                    && indy.bsmArgs[1] instanceof Handle h && h.getOwner().equals(target.owner())
                    && h.getName().equals(target.name()) && h.getDesc().equals(target.desc())
                    && (line < 0 || flow.lineOf(indy) == line)) {
                found.add(indy);
            }
        }
        return found;
    }

    /** Instructions in {@code flow} calling {@code target}, optionally only on {@code line} (ignored if negative). */
    public static List<MethodInsnNode> callInsns(MethodDataFlow flow, MethodRef target, int line) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode insn : flow.instructions()) {
            if (insn instanceof MethodInsnNode m && m.owner.equals(target.owner()) && m.name.equals(target.name())
                    && m.desc.equals(target.desc()) && (line < 0 || flow.lineOf(m) == line)) {
                calls.add(m);
            }
        }
        return calls;
    }
}
