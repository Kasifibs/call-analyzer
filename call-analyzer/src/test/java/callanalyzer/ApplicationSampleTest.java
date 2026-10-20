package callanalyzer;

import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallGraphAnalyzer;
import callanalyzer.analysis.ClassHierarchy;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.events.EventConfig;
import callanalyzer.events.EventDispatch;
import callanalyzer.model.ClassInfo;
import callanalyzer.model.MethodRef;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The application service / domain event sample as provided by the user (sample.app). */
class ApplicationSampleTest {

    private static AnalysisOptions options() {
        return new AnalysisOptions().entry("sample.app.ComplexService#handle").target("sample.app.IRepository")
                .events(new EventConfig("sample.app.DomainEvent", "sample.app.DomainEventDispatcher",
                        "sample.app.IObserver", null));
    }

    private static List<String> repositories(AnalysisResult r) {
        TreeSet<String> used = new TreeSet<>();
        r.findings().forEach(f -> f.matchedTargets().stream().map(DispatchTarget::receiverClass)
                .map(MethodRef::simpleName).forEach(used::add));
        return List.copyOf(used);
    }

    @Test
    void findsEveryRepositoryAndBothDomainEvents() {
        AnalysisResult r = new CallGraphAnalyzer(Fixtures.hierarchy(), options(), Fixtures.classBytes()).analyze();
        assertEquals(List.of("ComplexRepository", "RepositoryForInnerEventFlow", "RepositoryForInnerEventFlow2",
                "SimpleRepository", "SimpleRepository2"), repositories(r));
        assertEquals(List.of("A_DomainEvent", "B_DomainEvent"), r.events().stream()
                .flatMap(e -> e.eventClasses().stream()).map(MethodRef::simpleName).sorted().toList());
        for (EventDispatch e : r.events()) {
            assertEquals(1, e.observers().size(), e.toString()); // A -> InnerEventHandlerService, B -> ...Service2
        }
        assertTrue(r.warnings().stream().noneMatch(w -> w.contains("OLMAYAN")), r.warnings().toString());
    }

    @Test
    void classesMissingFromTheClassPathAreReported() {
        Map<String, ClassInfo> partial = new HashMap<>(Fixtures.hierarchy().classes());
        partial.remove("sample/app/SimpleService2");
        partial.remove("sample/app/EventEmitterService");
        AnalysisResult r = new CallGraphAnalyzer(new ClassHierarchy(partial), options(), Fixtures.classBytes())
                .analyze();
        String warning = r.warnings().stream().filter(w -> w.contains("OLMAYAN")).findFirst().orElseThrow();
        assertTrue(warning.contains("sample.app.SimpleService2") && warning.contains("sample.app.EventEmitterService"),
                warning);
    }
}
