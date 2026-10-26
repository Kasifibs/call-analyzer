package callanalyzer.dataflow;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * Finds the code of {@code finally} blocks. javac copies a finally block onto every normal exit of the try block and
 * once more into a catch-any exception handler that ends by rethrowing; that handler copy is what identifies it.
 */
public final class FinallyBlocks {

    private FinallyBlocks() {
    }

    /**
     * Instructions of the finally blocks whose try block ends after {@code from} (so code following or containing
     * {@code from} is protected by them).
     */
    public static Set<AbstractInsnNode> protecting(MethodDataFlow flow, AbstractInsnNode from) {
        InsnList insns = flow.instructions();
        int fromIndex = insns.indexOf(from);
        Set<AbstractInsnNode> code = new HashSet<>();
        for (TryCatchBlockNode tcb : flow.tryCatchBlocks()) {
            if (tcb.type != null || insns.indexOf(tcb.end) <= fromIndex) {
                continue;
            }
            Deque<AbstractInsnNode> work = new ArrayDeque<>();
            work.push(tcb.handler);
            while (!work.isEmpty()) {
                AbstractInsnNode insn = work.pop();
                if (insn == null || !code.add(insn)) {
                    continue;
                }
                int op = insn.getOpcode();
                if (insn instanceof JumpInsnNode j) {
                    work.push(j.label);
                    if (op == Opcodes.GOTO) {
                        continue;
                    }
                } else if (insn instanceof TableSwitchInsnNode t) {
                    work.push(t.dflt);
                    t.labels.forEach(work::push);
                    continue;
                } else if (insn instanceof LookupSwitchInsnNode l) {
                    work.push(l.dflt);
                    l.labels.forEach(work::push);
                    continue;
                } else if (op == Opcodes.ATHROW || (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)) {
                    continue;
                }
                work.push(insn.getNext());
            }
        }
        return code;
    }
}
