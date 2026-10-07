package callanalyzer.loader;

import callanalyzer.model.CallSite;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.InvokeKind;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns the bytes of a single {@code .class} file into a {@link ClassInfo}. */
public final class ClassInfoReader {

    private static final int ASM_API = Opcodes.ASM9;
    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private ClassInfoReader() {
    }

    public static ClassInfo read(byte[] classFile) {
        Collector collector = new Collector();
        new ClassReader(classFile).accept(collector, ClassReader.SKIP_FRAMES);
        return collector.toClassInfo();
    }

    private static final class Collector extends ClassVisitor {
        private String name;
        private int access;
        private String superName;
        private List<String> interfaces = List.of();
        private String sourceFile;
        private final Map<String, MethodInfo> methods = new LinkedHashMap<>();

        Collector() {
            super(ASM_API);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
                          String[] interfaces) {
            this.name = name;
            this.access = access;
            this.superName = superName;
            this.interfaces = interfaces == null ? List.of() : List.of(interfaces);
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
        }

        @Override
        public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature,
                                         String[] exceptions) {
            MethodRef ref = new MethodRef(name, methodName, descriptor);
            List<CallSite> calls = new ArrayList<>();
            methods.put(ref.key(), new MethodInfo(ref, access, calls));
            return new CallCollector(calls);
        }

        ClassInfo toClassInfo() {
            return new ClassInfo(name, access, superName, interfaces, Map.copyOf(methods), sourceFile);
        }
    }

    private static final class CallCollector extends MethodVisitor {
        private final List<CallSite> calls;
        private int currentLine = -1;

        CallCollector(List<CallSite> calls) {
            super(ASM_API);
            this.calls = calls;
        }

        @Override
        public void visitLineNumber(int line, Label start) {
            currentLine = line;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (owner.startsWith("[")) {
                return; // array pseudo-methods such as int[].clone()
            }
            InvokeKind kind = switch (opcode) {
                case Opcodes.INVOKESTATIC -> InvokeKind.STATIC;
                case Opcodes.INVOKESPECIAL -> InvokeKind.SPECIAL;
                case Opcodes.INVOKEINTERFACE -> InvokeKind.INTERFACE;
                default -> InvokeKind.VIRTUAL;
            };
            calls.add(new CallSite(kind, new MethodRef(owner, name, descriptor), currentLine, false));
        }

        /**
         * Lambdas and method references compile to {@code invokedynamic} with {@code LambdaMetafactory}; the
         * second bootstrap argument is a handle to the method that actually runs (the synthetic lambda body or
         * the referenced method). We treat it as a call from the enclosing method, so that e.g.
         * {@code cars.forEach(ICar::start)} or {@code cars.forEach(c -> c.start())} is followed.
         */
        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
            if (!LAMBDA_METAFACTORY.equals(bootstrap.getOwner()) || args.length < 2
                    || !(args[1] instanceof Handle impl)) {
                return;
            }
            InvokeKind kind = switch (impl.getTag()) {
                case Opcodes.H_INVOKESTATIC -> InvokeKind.STATIC;
                case Opcodes.H_INVOKESPECIAL, Opcodes.H_NEWINVOKESPECIAL -> InvokeKind.SPECIAL;
                case Opcodes.H_INVOKEINTERFACE -> InvokeKind.INTERFACE;
                case Opcodes.H_INVOKEVIRTUAL -> InvokeKind.VIRTUAL;
                default -> null; // field handles cannot appear here
            };
            if (kind == null || impl.getOwner().startsWith("[")) {
                return;
            }
            MethodRef target = new MethodRef(impl.getOwner(), impl.getName(), impl.getDesc());
            calls.add(new CallSite(kind, target, currentLine, true));
        }
    }
}
