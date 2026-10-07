package callanalyzer.model;

import org.objectweb.asm.Type;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Identifies a method by its owner (JVM internal name, e.g. {@code com/example/ICar}), name and descriptor.
 */
public record MethodRef(String owner, String name, String desc) implements Comparable<MethodRef> {

    public static String toDottedName(String internalName) {
        return internalName.replace('/', '.');
    }

    public static String simpleName(String internalName) {
        String dotted = toDottedName(internalName);
        return dotted.substring(dotted.lastIndexOf('.') + 1);
    }

    /** {@code name + desc}, the key a method is stored under inside its class. */
    public String key() {
        return name + desc;
    }

    public String ownerDotted() {
        return toDottedName(owner);
    }

    public boolean isConstructor() {
        return "<init>".equals(name);
    }

    /** {@code com.example.Garage#service(com.example.ICar)} */
    public String toQualifiedString() {
        return ownerDotted() + "#" + name + "(" + argumentList(false) + ")";
    }

    /** {@code Garage#service(ICar)} */
    public String toShortString() {
        return simpleName(owner) + "#" + name + "(" + argumentList(true) + ")";
    }

    private String argumentList(boolean simple) {
        return Arrays.stream(Type.getArgumentTypes(desc))
                .map(Type::getClassName)
                .map(n -> simple ? n.substring(n.lastIndexOf('.') + 1) : n)
                .collect(Collectors.joining(", "));
    }

    @Override
    public int compareTo(MethodRef o) {
        int c = owner.compareTo(o.owner);
        if (c != 0) return c;
        c = name.compareTo(o.name);
        return c != 0 ? c : desc.compareTo(o.desc);
    }

    @Override
    public String toString() {
        return owner + "." + name + desc;
    }
}
