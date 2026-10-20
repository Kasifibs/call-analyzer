package callanalyzer.dataflow;

import callanalyzer.model.MethodRef;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Result of running {@link SourceTrackingInterpreter} over one method body. */
public final class MethodDataFlow {

    private final MethodRef method;
    private final String sourceFile;
    private final MethodNode node;
    private final Frame<TrackedValue>[] frames;

    private MethodDataFlow(MethodRef method, String sourceFile, MethodNode node, Frame<TrackedValue>[] frames) {
        this.method = method;
        this.sourceFile = sourceFile;
        this.node = node;
        this.frames = frames;
    }

    public static Optional<MethodDataFlow> analyze(ClassNode owner, MethodNode node) {
        if (node.instructions.size() == 0) {
            return Optional.empty();
        }
        try {
            Frame<TrackedValue>[] frames =
                    new Analyzer<>(new SourceTrackingInterpreter(node.instructions)).analyze(owner.name, node);
            return Optional.of(new MethodDataFlow(new MethodRef(owner.name, node.name, node.desc),
                    owner.sourceFile, node, frames));
        } catch (AnalyzerException e) {
            return Optional.empty();
        }
    }

    public MethodRef method() {
        return method;
    }

    public String sourceFile() {
        return sourceFile;
    }

    public InsnList instructions() {
        return node.instructions;
    }

    public AbstractInsnNode insn(int index) {
        return node.instructions.get(index);
    }

    public int indexOf(AbstractInsnNode insn) {
        return node.instructions.indexOf(insn);
    }

    /** The {@code depth}-th value from the top of the operand stack before {@code insn} (0 = top). */
    public TrackedValue stack(AbstractInsnNode insn, int depth) {
        Frame<TrackedValue> f = frames[indexOf(insn)];
        if (f == null || f.getStackSize() <= depth) {
            return null;
        }
        return f.getStack(f.getStackSize() - 1 - depth);
    }

    /** Receiver of a non-static call, {@code null} for static calls or unreachable code. */
    public TrackedValue receiver(MethodInsnNode call) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) {
            return null;
        }
        return stack(call, Type.getArgumentTypes(call.desc).length);
    }

    /** Arguments of a call, in declaration order; empty for unreachable code. */
    public List<TrackedValue> arguments(MethodInsnNode call) {
        int n = Type.getArgumentTypes(call.desc).length;
        List<TrackedValue> args = new ArrayList<>(n);
        for (int i = n - 1; i >= 0; i--) {
            TrackedValue v = stack(call, i);
            if (v == null) {
                return List.of();
            }
            args.add(v);
        }
        return args;
    }

    /** Values returned by {@code ARETURN} instructions. */
    public List<TrackedValue> returnValues() {
        List<TrackedValue> values = new ArrayList<>();
        for (AbstractInsnNode insn : node.instructions) {
            if (insn.getOpcode() == Opcodes.ARETURN) {
                TrackedValue v = stack(insn, 0);
                if (v != null) {
                    values.add(v);
                }
            }
        }
        return values;
    }

    /** Converts a local variable slot of a parameter into its 0-based parameter index ({@code -1} if not one). */
    public int parameterIndex(int local) {
        int slot = (node.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        Type[] args = Type.getArgumentTypes(node.desc);
        for (int i = 0; i < args.length; i++) {
            if (slot == local) {
                return i;
            }
            slot += args[i].getSize();
        }
        return -1;
    }

    /** Name of a parameter from debug info, or {@code null}. */
    public String parameterName(int local) {
        if (node.localVariables != null) {
            for (var lv : node.localVariables) {
                if (lv.index == local) {
                    return lv.name;
                }
            }
        }
        return null;
    }

    public int lineOf(AbstractInsnNode insn) {
        for (AbstractInsnNode p = insn; p != null; p = p.getPrevious()) {
            if (p instanceof LineNumberNode ln) {
                return ln.line;
            }
        }
        return -1;
    }
}
