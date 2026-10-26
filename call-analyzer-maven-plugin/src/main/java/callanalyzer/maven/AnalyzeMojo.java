package callanalyzer.maven;

import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.AcquireExemption;
import callanalyzer.acquire.AcquireRelease;
import callanalyzer.acquire.ClassUsage;
import callanalyzer.acquire.ReleaseCheck;
import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.events.EventConfig;
import callanalyzer.loader.ClassPathLoader;
import callanalyzer.model.MethodRef;
import callanalyzer.report.HtmlScanReport;
import callanalyzer.report.ScanReport;
import callanalyzer.scan.AcquireScanner;
import callanalyzer.scan.Candidate;
import callanalyzer.scan.ScanItem;
import callanalyzer.scan.ScanItem.Status;
import callanalyzer.scan.ScanResult;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Finds every method of the built artifact that acquires cars (calls the acquirer method or carries the acquire
 * annotation), analyses the flow of each one and fails the build if a method certainly uses a class it has not
 * acquired. Reports are written to {@code target/call-analyzer/}.
 *
 * <p>The project's runtime dependencies (including other modules of a multi-module build) are put on the class path,
 * so flows into them are followed; only the project's own classes are scanned for acquiring methods.
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.RUNTIME)
public class AnalyzeMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Skips the analysis. */
    @Parameter(property = "callanalyzer.skip", defaultValue = "false")
    private boolean skip;

    /** Types whose implementations are looked for, e.g. {@code com.example.ICar}. */
    @Parameter(required = true)
    private List<String> targets;

    /** Acquirer method, e.g. {@code com.example.CarAcquirer#acquire}. */
    @Parameter(property = "callanalyzer.acquirer")
    private String acquirer;

    /** Acquire annotation, e.g. {@code com.example.AcquireCar}. */
    @Parameter(property = "callanalyzer.acquireAnnotation")
    private String acquireAnnotation;

    /** Only scan and descend into classes in these packages (recommended: your own root package). */
    @Parameter
    private List<String> includes;

    /** Never descend into classes in these packages. */
    @Parameter
    private List<String> excludes;

    @Parameter(defaultValue = "-1")
    private int maxDepth;

    @Parameter(defaultValue = "false")
    private boolean directOnly;

    @Parameter(defaultValue = "false")
    private boolean onlyInterfaceMethods;

    @Parameter(defaultValue = "false")
    private boolean skipInternal;

    @Parameter(defaultValue = "false")
    private boolean includeConstructors;

    /** Event tracking: base event class. Together with {@link #eventDispatcher} and {@link #eventObserver}. */
    @Parameter
    private String eventClass;

    @Parameter
    private String eventDispatcher;

    @Parameter
    private String eventObserver;

    @Parameter(defaultValue = "handle")
    private String eventHandleMethod;

    /** {@code exact} or {@code assignable}. */
    @Parameter(defaultValue = "exact")
    private String eventMatch;

    /** {@code new} or {@code dispatch}. */
    @Parameter(defaultValue = "new")
    private String eventDetection;

    /**
     * Release method, e.g. {@code com.example.LockAcquirer#release}: every element a method acquires with the
     * {@link #acquirer} must be released with it in a {@code finally} block of the same method. Acquire annotations
     * are not checked.
     */
    @Parameter(property = "callanalyzer.releaser")
    private String releaser;

    /** Fail the build if an acquired element is not released (see {@link #releaser}). */
    @Parameter(property = "callanalyzer.failOnUnreleased", defaultValue = "true")
    private boolean failOnUnreleased;

    /**
     * Classes a method may use without acquiring them: they are reported as exempt and never fail the build. A class
     * also exempts its subclasses (an interface all its implementations). See {@link Exemption}.
     */
    @Parameter
    private List<Exemption> exemptions;

    /** Fail the build if a method certainly uses a class it has not acquired. */
    @Parameter(property = "callanalyzer.failOnMissing", defaultValue = "true")
    private boolean failOnMissing;

    /** Also fail the build for classes that are only possibly missing (calls through an interface). */
    @Parameter(property = "callanalyzer.failOnPossiblyMissing", defaultValue = "false")
    private boolean failOnPossiblyMissing;

    /** Put the project's runtime dependencies on the analysis class path. */
    @Parameter(defaultValue = "true")
    private boolean includeDependencies;

    /**
     * Warn when a flow calls a class of the {@link #includes} packages that is not on the class path. With
     * {@link #includeDependencies} off this is summarised in one line; {@code false} logs it only at debug level.
     */
    @Parameter(property = "callanalyzer.warnMissingClasses", defaultValue = "true")
    private boolean warnMissingClasses;

    /** What to analyse; by default the project's jar, or its class directory if no jar has been built. */
    @Parameter
    private File analyzedFile;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/call-analyzer")
    private File reportDirectory;

    /** Also write the HTML report (overview page plus one page per method). */
    @Parameter(defaultValue = "true")
    private boolean htmlReport;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("call-analyzer atlandi (callanalyzer.skip).");
            return;
        }
        if ("pom".equals(project.getPackaging())) {
            getLog().info("call-analyzer: pom projesi, analiz edilecek kod yok.");
            return;
        }
        if (acquirer == null && acquireAnnotation == null) {
            throw new MojoExecutionException("call-analyzer: <acquirer> ve/veya <acquireAnnotation> verilmeli.");
        }
        if (releaser != null && acquirer == null) {
            throw new MojoExecutionException("call-analyzer: <releaser> icin <acquirer> da verilmeli.");
        }
        if (targets == null || targets.isEmpty()) {
            throw new MojoExecutionException("call-analyzer: en az bir <target> verilmeli (or. com.example.ICar).");
        }
        Path main = mainArtifact();
        if (main == null) {
            getLog().warn("call-analyzer: analiz edilecek jar ya da sinif dizini yok, atlandi.");
            return;
        }
        try {
            ScanResult result = scan(main);
            report(result);
        } catch (IOException e) {
            throw new MojoExecutionException("call-analyzer: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("call-analyzer: " + e.getMessage(), e);
        }
    }

    private Path mainArtifact() {
        if (analyzedFile != null) {
            return analyzedFile.toPath();
        }
        File jar = project.getArtifact() == null ? null : project.getArtifact().getFile();
        if (jar != null && jar.isFile()) {
            return jar.toPath();
        }
        if (outputDirectory != null && outputDirectory.isDirectory()) {
            getLog().info("call-analyzer: jar bulunamadi, sinif dizini analiz ediliyor: " + outputDirectory);
            return outputDirectory.toPath();
        }
        return null;
    }

    private ScanResult scan(Path main) throws IOException, MojoExecutionException {
        Set<String> ownClasses = ClassPathLoader.load(List.of(main)).classes().keySet();

        List<Path> classPath = new ArrayList<>();
        classPath.add(main);
        if (includeDependencies) {
            try {
                for (String element : project.getRuntimeClasspathElements()) {
                    Path p = Path.of(element);
                    if (!p.equals(outputDirectory.toPath()) && !p.equals(main) && Files.exists(p)) {
                        classPath.add(p);
                    }
                }
            } catch (DependencyResolutionRequiredException e) {
                throw new MojoExecutionException("call-analyzer: bagimliliklar cozulemedi", e);
            }
        }
        getLog().info("call-analyzer: " + main.getFileName() + " analiz ediliyor (" + (classPath.size() - 1)
                + " bagimlilik sinif yolunda)");
        ClassPathLoader loader = ClassPathLoader.load(classPath);
        loader.warnings().forEach(w -> getLog().debug(w));
        ClassHierarchy hierarchy = new ClassHierarchy(loader.classes());

        AnalysisOptions options = new AnalysisOptions().maxDepth(maxDepth).directOnly(directOnly)
                .onlyInterfaceMethods(onlyInterfaceMethods).skipInternalCalls(skipInternal)
                .includeConstructors(includeConstructors);
        targets.forEach(options::target);
        if (includes != null) includes.forEach(options::include);
        if (excludes != null) excludes.forEach(options::exclude);
        if (eventClass != null || eventDispatcher != null || eventObserver != null) {
            options.events(new EventConfig(eventClass, eventDispatcher, eventObserver, eventHandleMethod,
                    !"assignable".equals(eventMatch),
                    "dispatch".equals(eventDetection) ? EventConfig.Detection.DISPATCH : EventConfig.Detection.NEW));
        }

        List<AcquireExemption> exempt = acquireExemptions();
        AcquireScanner scanner = new AcquireScanner(hierarchy, loader.classBytes(), options, acquirer,
                acquireAnnotation).withExemptions(exempt).withReleaser(releaser);
        // Only this project's own methods are scanned; dependencies are analysed where the flow leads into them.
        List<Candidate> candidates = scanner.candidates().stream()
                .filter(c -> ownClasses.contains(c.method().owner()))
                .toList();
        getLog().info("call-analyzer: acquire eden " + candidates.size() + " metot bulundu");
        AcquireExemption.problems(hierarchy, exempt, candidates.stream().map(Candidate::method).toList())
                .forEach(w -> getLog().warn("call-analyzer: " + w));
        if (candidates.isEmpty()) {
            getLog().warn("call-analyzer: acquire eden metot bulunamadi; <acquirer>/<acquireAnnotation> ve <includes>"
                    + " ayarlarini kontrol edin.");
        }

        Files.createDirectories(reportDirectory.toPath());
        HtmlScanReport html = htmlReport ? new HtmlScanReport(reportDirectory.toPath().resolve("tarama.html")) : null;
        // Without dependencies every flow into another module reaches a missing class, so one summary line is
        // enough; the per-method details stay available with mvn -X.
        boolean warnEach = warnMissingClasses && includeDependencies;
        Set<MethodRef> leavingClassPath = new HashSet<>();
        ScanResult result = scanner.run(candidates, getLog()::debug, (item, analysis) -> {
            if (html != null) {
                html.writeMethodPage(item, analysis);
            }
            analysis.warnings().stream().filter(w -> w.contains("OLMAYAN")).distinct().forEach(w -> {
                String line = item.candidate().method().toShortString() + ": " + w;
                if (warnEach) {
                    getLog().warn(line);
                } else {
                    getLog().debug(line);
                }
                leavingClassPath.add(item.candidate().method());
            });
        });
        if (warnMissingClasses && !includeDependencies && !leavingClassPath.isEmpty()) {
            getLog().warn("call-analyzer: " + leavingClassPath.size() + " metotta akis sinif yolunda olmayan"
                    + " siniflara geciyor (includeDependencies=false), iclerine girilmedi. Ayrintilar icin mvn -X;"
                    + " bu uyariyi kapatmak icin <warnMissingClasses>false</warnMissingClasses>.");
        }
        if (html != null) {
            html.writeIndex(result);
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportDirectory.toPath().resolve("tarama.txt"),
                StandardCharsets.UTF_8))) {
            ScanReport.writeText(result, out);
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(reportDirectory.toPath().resolve("tarama.json"),
                StandardCharsets.UTF_8))) {
            ScanReport.writeJson(result, out);
        }
        return result;
    }

    private void report(ScanResult result) throws MojoFailureException {
        long missing = count(result, c -> !c.missing().isEmpty());
        long possible = count(result, c -> !c.possiblyMissing().isEmpty() || !c.undecided().isEmpty());
        long unreleased = count(result, c -> c.release() != null && !c.release().unreleased().isEmpty());
        getLog().info("call-analyzer: " + result.items().size() + " metot: " + missing + " kesin eksik, " + possible
                + " olasi eksik, " + (releaser == null ? "" : unreleased + " release eksik, ")
                + result.count(Status.OK) + " OK" + (result.count(Status.ERROR) > 0
                ? ", " + result.count(Status.ERROR) + " hata" : ""));
        for (ScanItem item : result.items()) {
            String method = item.candidate().method().toQualifiedString();
            AcquireComparison c = item.comparison();
            if (c == null) {
                getLog().warn("  [HATA] " + method + ": " + item.error());
                continue;
            }
            if (!c.exempt().isEmpty()) {
                getLog().info("  [istisna] " + method + "  acquire edilmeden izin verilen: " + names(c.exempt()));
            }
            if (!c.missing().isEmpty()) {
                getLog().error("  [X] " + method + "  kesin eksik: " + names(c.missing()));
            }
            if (!c.possiblyMissing().isEmpty() || !c.undecided().isEmpty()) {
                getLog().warn("  [?] " + method + "  olasi eksik/belirsiz: " + names(c.possiblyMissing())
                        + (c.undecided().isEmpty() ? "" : " " + names(c.undecided())));
            }
            ReleaseCheck r = c.release();
            if (r != null) {
                if (!r.unreleased().isEmpty()) {
                    getLog().error("  [R] " + method + "  release edilmeyen: " + elements(r.unreleased()));
                }
                if (!r.notInFinally().isEmpty()) {
                    getLog().warn("  [R?] " + method + "  finally disinda release edilen: "
                            + elements(r.notInFinally()));
                }
                if (!r.undecided().isEmpty()) {
                    getLog().warn("  [R?] " + method + "  release'i belirlenemeyen: " + elements(r.undecided()));
                }
            }
        }
        Path index = reportDirectory.toPath().resolve(htmlReport ? "tarama.html" : "tarama.txt");
        getLog().info("call-analyzer raporu: " + index);
        List<String> failures = new ArrayList<>();
        if (failOnMissing && missing > 0) {
            failures.add(missing + " metotta acquire edilmeden kullanilan sinif var (kesin eksik)");
        }
        if (failOnPossiblyMissing && possible > 0) {
            failures.add(possible + " metotta olasi eksik acquire var");
        }
        if (failOnUnreleased && unreleased > 0) {
            failures.add(unreleased + " metotta acquire edilip release edilmeyen var");
        }
        if (!failures.isEmpty()) {
            throw new MojoFailureException("call-analyzer: " + String.join("; ", failures) + ". Ayrintilar: "
                    + index);
        }
    }

    private static long count(ScanResult result, Predicate<AcquireComparison> condition) {
        return result.items().stream().filter(i -> i.comparison() != null && condition.test(i.comparison())).count();
    }

    private static String elements(List<AcquireRelease> elements) {
        return elements.stream()
                .map(e -> (e.car().isResolved() ? MethodRef.simpleName(e.car().concreteClass()) : "?")
                        + " (satir " + e.acquireLine() + ")")
                .distinct().collect(Collectors.joining(", "));
    }

    private List<AcquireExemption> acquireExemptions() throws MojoExecutionException {
        if (exemptions == null) {
            return List.of();
        }
        List<AcquireExemption> result = new ArrayList<>();
        for (Exemption e : exemptions) {
            try {
                result.add(new AcquireExemption(e.getMethod(), e.getClasses()));
            } catch (IllegalArgumentException ex) {
                throw new MojoExecutionException("call-analyzer: gecersiz <exemption>: " + ex.getMessage(), ex);
            }
        }
        return result;
    }

    private static String names(List<ClassUsage> usages) {
        return usages.stream().map(u -> MethodRef.simpleName(u.className())).collect(Collectors.joining(", "));
    }
}
