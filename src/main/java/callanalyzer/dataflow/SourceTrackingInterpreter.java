package callanalyzer.dataflow;

import callanalyzer.dataflow.ValueSource.CallResult;
import callanalyzer.dataflow.ValueSource.Constant;
import callanalyzer.dataflow.ValueSource.FieldValue;
import callanalyzer.dataflow.ValueSource.LambdaValue;
import callanalyzer.dataflow.ValueSource.NewObject;
import callanalyzer.dataflow.ValueSource.Parameter;
import callanalyzer.dataflow.ValueSource.This;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ASM interpreter that records, for every reference value, the set of {@link ValueSource}s it may originate from.
 * Sizes and merging of the underlying JVM values are delegated to {@link BasicInterpreter}. Casts and copies keep
 * the sources, so {@code (Bmw) x} still points at where {@code x} came from.
 */
final class SourceTrackingInterpreter extends Interpreter<TrackedValue> {

    private final BasicInterpreter basic = new BasicInterpreter();
    private final InsnList instructions;

    SourceTrackingInterpreter(InsnList instructions) {
        super(Opcodes.ASM9);
        this.instructions = instructions;
    }

    private static TrackedValue of(BasicValue b) {
        return b == null ? null : new TrackedValue(b, Set.of());
    }

    private static TrackedValue of(BasicValue b, ValueSource source) {
        return b == null ? null : new TrackedValue(b, Set.of(source));
    }

    private static BasicValue unwrap(TrackedValue v) {
        return v == null ? null : v.basic();
    }

    @Override
    public TrackedValue newValue(Type type) {
        return of(basic.newValue(type));
    }

    @Override
    public TrackedValue newParameterValue(boolean isInstanceMethod, int local, Type type) {
        BasicValue b = basic.newValue(type);
        if (isInstanceMethod && local == 0) {
            return of(b, new This());
        }
        return of(b, new Parameter(local, type.getDescriptor()));
    }

    @Override
    public TrackedValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
        BasicValue b = basic.newOperation(insn);
        return switch (insn.getOpcode()) {
            case Opcodes.NEW -> of(b, new NewObject(instructions.indexOf(insn), ((TypeInsnNode) insn).desc));
            case Opcodes.GETSTATIC -> of(b, field((FieldInsnNode) insn));
            case Opcodes.LDC -> {
                Object c = ((LdcInsnNode) insn).cst;
                yield c instanceof String || c instanceof Type ? of(b, new Constant(c)) : of(b);
            }
            default -> of(b);
        };
    }

    @Override
    public TrackedValue copyOperation(AbstractInsnNode insn, TrackedValue value) throws AnalyzerException {
        return new TrackedValue(basic.copyOperation(insn, value.basic()), value.sources());
    }

    @Override
    public TrackedValue unaryOperation(AbstractInsnNode insn, TrackedValue value) throws AnalyzerException {
        BasicValue b = basic.unaryOperation(insn, value.basic());
        return switch (insn.getOpcode()) {
            case Opcodes.CHECKCAST -> b == null ? null : new TrackedValue(b, value.sources());
            case Opcodes.GETFIELD -> of(b, field((FieldInsnNode) insn));
            case Opcodes.ANEWARRAY -> of(b, new NewObject(instructions.indexOf(insn),
                    "[" + Type.getObjectType(((TypeInsnNode) insn).desc).getDescriptor()));
            default -> of(b);
        };
    }

    @Override
    public TrackedValue binaryOperation(AbstractInsnNode insn, TrackedValue v1, TrackedValue v2)
            throws AnalyzerException {
        return of(basic.binaryOperation(insn, v1.basic(), v2.basic()));
    }

    @Override
    public TrackedValue ternaryOperation(AbstractInsnNode insn, TrackedValue v1, TrackedValue v2, TrackedValue v3)
            throws AnalyzerException {
        return of(basic.ternaryOperation(insn, v1.basic(), v2.basic(), v3.basic()));
    }

    @Override
    public TrackedValue naryOperation(AbstractInsnNode insn, List<? extends TrackedValue> values)
            throws AnalyzerException {
        BasicValue b = basic.naryOperation(insn, values.stream().map(SourceTrackingInterpreter::unwrap).toList());
        if (insn instanceof MethodInsnNode m) {
            return of(b, new CallResult(instructions.indexOf(insn), new MethodRef(m.owner, m.name, m.desc)));
        }
        if (insn instanceof InvokeDynamicInsnNode) {
            return of(b, new LambdaValue(instructions.indexOf(insn)));
        }
        return of(b);
    }

    @Override
    public void returnOperation(AbstractInsnNode insn, TrackedValue value, TrackedValue expected) {
    }

    @Override
    public TrackedValue merge(TrackedValue v, TrackedValue w) {
        BasicValue b = basic.merge(v.basic(), w.basic());
        if (b.equals(v.basic()) && v.sources().containsAll(w.sources())) {
            return v;
        }
        Set<ValueSource> union = new LinkedHashSet<>(v.sources());
        union.addAll(w.sources());
        return new TrackedValue(b, Collections.unmodifiableSet(union));
    }

    private static FieldValue field(FieldInsnNode f) {
        return new FieldValue(f.owner, f.name, f.desc);
    }
}
