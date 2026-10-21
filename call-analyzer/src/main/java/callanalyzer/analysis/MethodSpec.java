package callanalyzer.analysis;

import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodInfo;
import callanalyzer.model.MethodRef;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A user supplied method selector. Accepted forms:
 * <ul>
 *   <li>{@code com.example.Garage} &ndash; every method declared in the class</li>
 *   <li>{@code com.example.Garage#service} &ndash; every overload named {@code service}</li>
 *   <li>{@code com.example.Garage#service(ICar, int)} &ndash; Java style parameter list, simple or qualified names</li>
 *   <li>{@code com.example.Garage#service(Lcom/example/ICar;I)V} &ndash; JVM descriptor</li>
 * </ul>
 * {@code ::} may be used instead of {@code #}.
 */
public record MethodSpec(String text, String className, String methodName, String descriptorPrefix,
                         List<String> parameterTypes) {

    public static MethodSpec parse(String text) {
        String s = text.trim();
        String cls;
        String member = null;
        int sep = s.indexOf('#');
        int sepLen = 1;
        if (sep < 0) {
            sep = s.indexOf("::");
            sepLen = 2;
        }
        if (sep < 0 && s.indexOf('(') > 0) {
            // com.example.Garage.service(ICar)
            sep = s.lastIndexOf('.', s.indexOf('('));
            sepLen = 1;
        }
        if (sep >= 0) {
            cls = s.substring(0, sep);
            member = s.substring(sep + sepLen);
        } else {
            cls = s;
        }
        if (cls.isBlank()) {
            throw new IllegalArgumentException("Metot taniminda sinif adi yok: " + text);
        }
        if (member == null || member.isBlank()) {
            return new MethodSpec(text, cls, null, null, null);
        }
        int open = member.indexOf('(');
        if (open < 0) {
            return new MethodSpec(text, cls, member, null, null);
        }
        int close = member.lastIndexOf(')');
        if (close < open) {
            throw new IllegalArgumentException("Parantezler eslesmiyor: " + text);
        }
        String name = member.substring(0, open);
        String inside = member.substring(open + 1, close);
        String after = member.substring(close + 1);
        if (!after.isBlank() || inside.contains("/") || inside.contains(";")) {
            return new MethodSpec(text, cls, name, member.substring(open, close + 1) + after.trim(), null);
        }
        List<String> params = new ArrayList<>();
        for (String p : splitTopLevel(inside)) {
            String t = p.trim().replaceAll("<.*>", "").replace("...", "[]").replace('$', '.');
            // allow "ICar car" (with a parameter name)
            String[] words = t.split("\\s+");
            t = words.length > 1 && !words[words.length - 1].endsWith("]") ? words[words.length - 2] : words[words.length - 1];
            if (!t.isEmpty()) {
                params.add(t);
            }
        }
        return new MethodSpec(text, cls, name, null, List.copyOf(params));
    }

    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (!s.substring(start).isBlank()) {
            parts.add(s.substring(start));
        }
        return parts;
    }

    /** Resolves the selector against the loaded classes; fails with a helpful message if nothing matches. */
    public List<MethodRef> resolve(ClassHierarchy hierarchy) {
        String internal = hierarchy.resolveClassName(className);
        ClassInfo cls = hierarchy.get(internal);
        if (cls == null) {
            throw new IllegalArgumentException("Giris sinifi sinif yolunda bulunamadi: " + className);
        }
        List<MethodRef> matches = cls.allMethods().stream()
                .map(MethodInfo::ref)
                .filter(this::matches)
                .sorted(Comparator.comparing(MethodRef::name).thenComparing(MethodRef::desc))
                .toList();
        if (matches.isEmpty()) {
            String available = cls.allMethods().stream()
                    .map(m -> "  " + m.ref().toQualifiedString())
                    .sorted()
                    .collect(Collectors.joining("\n"));
            throw new IllegalArgumentException("'" + text + "' ile eslesen metot yok. "
                    + MethodRef.toDottedName(internal) + " sinifindaki metotlar:\n" + available);
        }
        return matches;
    }

    /** Whether the selector names {@code ref}: same class (resolved like {@link #resolve}) and a matching method. */
    public boolean selects(ClassHierarchy hierarchy, MethodRef ref) {
        return hierarchy.resolveClassName(className).equals(ref.owner()) && matches(ref);
    }

    /** Whether {@code ref}'s name and parameters match, ignoring its owner class. */
    public boolean matchesSignature(MethodRef ref) {
        return methodName != null && matches(ref);
    }

    boolean matches(MethodRef ref) {
        if (methodName == null) {
            return !"<clinit>".equals(ref.name());
        }
        String wanted = methodName.equals(MethodRef.simpleName(className)) ? "<init>" : methodName;
        if (!ref.name().equals(methodName) && !ref.name().equals(wanted)) {
            return false;
        }
        if (descriptorPrefix != null) {
            return ref.desc().startsWith(descriptorPrefix);
        }
        if (parameterTypes == null) {
            return true;
        }
        Type[] args = Type.getArgumentTypes(ref.desc());
        if (args.length != parameterTypes.size()) {
            return false;
        }
        return Arrays.stream(args).map(t -> t.getClassName().replace('$', '.')).toList().equals(parameterTypes)
                || matchesLoosely(args);
    }

    private boolean matchesLoosely(Type[] args) {
        for (int i = 0; i < args.length; i++) {
            String actual = args[i].getClassName().replace('$', '.');
            String given = parameterTypes.get(i);
            if (!actual.equals(given) && !actual.endsWith("." + given)) {
                return false;
            }
        }
        return true;
    }
}
