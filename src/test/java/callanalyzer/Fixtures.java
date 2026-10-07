package callanalyzer;

import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallGraphAnalyzer;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.Finding;
import callanalyzer.loader.ClassPathLoader;
import sample.cars.ICar;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Loads the compiled {@code sample.cars} fixture application once and runs analyses on it. */
public final class Fixtures {

    private static ClassHierarchy hierarchy;
    private static Map<String, byte[]> classBytes;

    private Fixtures() {
    }

    public static Path sampleClassesDir() {
        try {
            return Path.of(ICar.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static synchronized ClassHierarchy hierarchy() {
        if (hierarchy == null) {
            try {
                ClassPathLoader loader = ClassPathLoader.load(List.of(sampleClassesDir()));
                hierarchy = new ClassHierarchy(loader.classes());
                classBytes = loader.classBytes();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return hierarchy;
    }

    public static synchronized Map<String, byte[]> classBytes() {
        hierarchy();
        return classBytes;
    }

    public static AnalysisOptions fromMain() {
        return new AnalysisOptions().entry("sample.cars.App#main").target("sample.cars.ICar");
    }

    public static AnalysisResult analyze(AnalysisOptions options) {
        return new CallGraphAnalyzer(hierarchy(), options).analyze();
    }

    /** Findings whose caller is {@code callerClass#callerMethod} and that call {@code calledMethod}. */
    public static List<Finding> find(AnalysisResult r, String callerClass, String callerMethod, String calledMethod) {
        return r.findings().stream()
                .filter(f -> f.caller().owner().equals("sample/cars/" + callerClass))
                .filter(f -> f.caller().name().equals(callerMethod))
                .filter(f -> f.site().target().name().equals(calledMethod))
                .toList();
    }
}
