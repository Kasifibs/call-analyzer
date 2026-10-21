package callanalyzer.acquire;

import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.MethodSpec;
import callanalyzer.model.MethodRef;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Classes a method is allowed to use without acquiring them: they are left out of the missing / possibly missing
 * lists of that method's comparison and reported as exempt instead. A class also exempts its subclasses (an
 * interface exempts all its implementations).
 *
 * @param method  method selector in {@link MethodSpec} form; a class name alone exempts every method of the class
 * @param classes class names (dotted or internal)
 */
public record AcquireExemption(String method, List<String> classes) {

    public AcquireExemption {
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("Istisnada metot verilmeli.");
        }
        if (classes == null || classes.stream().allMatch(c -> c == null || c.isBlank())) {
            throw new IllegalArgumentException("Istisnada en az bir sinif verilmeli: " + method);
        }
        MethodSpec.parse(method); // fail early on a malformed selector
        method = method.strip();
        classes = classes.stream().filter(c -> c != null && !c.isBlank()).map(String::strip).toList();
    }

    /** Parses {@code com.example.RentalService#rent=com.example.Tesla,com.example.Opel}. */
    public static AcquireExemption parse(String text) {
        int eq = text.indexOf('=');
        if (eq < 0) {
            throw new IllegalArgumentException("Gecersiz istisna (metot=Sinif1,Sinif2 bekleniyor): " + text);
        }
        return new AcquireExemption(text.substring(0, eq), List.of(text.substring(eq + 1).split(",")));
    }

    /** Whether the exemption is for {@code entry}. */
    public boolean appliesTo(ClassHierarchy hierarchy, MethodRef entry) {
        return MethodSpec.parse(method).selects(hierarchy, entry);
    }

    /** Internal names of the exempt classes for the given entry methods. */
    public static List<String> classesFor(ClassHierarchy hierarchy, Collection<AcquireExemption> exemptions,
                                          Collection<MethodRef> entries) {
        List<String> result = new ArrayList<>();
        for (AcquireExemption e : exemptions) {
            if (entries.stream().anyMatch(entry -> e.appliesTo(hierarchy, entry))) {
                e.classes().stream().map(hierarchy::resolveClassName).filter(c -> !result.contains(c))
                        .forEach(result::add);
            }
        }
        return result;
    }

    /**
     * Mistakes worth a warning: an exemption that matches none of the analysed methods, or names a class that is not
     * on the class path (a typo, or the class is in a module that is not on the class path).
     */
    public static List<String> problems(ClassHierarchy hierarchy, Collection<AcquireExemption> exemptions,
                                        Collection<MethodRef> analysed) {
        List<String> problems = new ArrayList<>();
        for (AcquireExemption e : exemptions) {
            if (analysed.stream().noneMatch(m -> e.appliesTo(hierarchy, m))) {
                problems.add("Istisna analiz edilen hicbir metotla eslesmedi: " + e.method());
            }
            for (String cls : e.classes()) {
                if (!hierarchy.contains(hierarchy.resolveClassName(cls))) {
                    problems.add("Istisnadaki sinif sinif yolunda yok: " + cls + " (" + e.method() + ")");
                }
            }
        }
        return problems;
    }
}
