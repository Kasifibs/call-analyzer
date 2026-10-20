package callanalyzer;

import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.ChainStep;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.analysis.Finding;
import callanalyzer.model.InvokeKind;
import callanalyzer.model.MethodRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static callanalyzer.Fixtures.analyze;
import static callanalyzer.Fixtures.find;
import static callanalyzer.Fixtures.fromMain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallGraphAnalyzerTest {

    private static List<String> chainNames(Finding f) {
        return f.chain().stream().map(ChainStep::method).map(MethodRef::toShortString).toList();
    }

    @Test
    void findsDirectInterfaceCallWithAllImplementations() {
        AnalysisResult r = analyze(fromMain());
        List<Finding> starts = find(r, "Garage", "service", "start");
        assertEquals(1, starts.size());
        Finding f = starts.get(0);
        assertEquals(InvokeKind.INTERFACE, f.site().kind());
        assertTrue(f.receiverTypeMatches());
        assertTrue(f.interfaceMethod());
        assertEquals(8, f.site().line());
        assertEquals(List.of("sample/cars/Bmw", "sample/cars/Tesla", "sample/cars/TowTruck"),
                f.matchedTargets().stream().map(DispatchTarget::receiverClass).toList());
        // Bmw and TowTruck inherit start() from AbstractCar
        assertEquals("sample/cars/AbstractCar", f.matchedTargets().get(0).method().owner());
    }

    @Test
    void descendsRecursivelyIntoCalleesAndReportsShortestChain() {
        AnalysisResult r = analyze(fromMain());
        Finding f = find(r, "Mechanic", "check", "brand").get(0);
        assertEquals(4, f.depth());
        assertEquals(List.of("App#main(String[])", "Dealer#sell(Bmw)", "Garage#service(ICar)",
                "Garage#inspect(ICar)", "Mechanic#check(ICar, int)"), chainNames(f));
        assertEquals(List.of(-1, 8, 10, 7, 12), f.chain().stream().map(ChainStep::callLine).toList());
    }

    @Test
    void descendsIntoImplementationsOfInterfaceCalls() {
        AnalysisResult r = analyze(fromMain());
        // App -> Dealer.sell -> Garage.service -> ICar.start => AbstractCar.start -> Engine.ignite -> Diagnostics.report
        Finding f = find(r, "Diagnostics", "report", "brand").get(0);
        assertEquals(5, f.depth());
        assertEquals("AbstractCar#start()", chainNames(f).get(3));
        // ... and inside an ICar implementation that calls another ICar
        Finding tow = find(r, "TowTruck", "tow", "stop").get(0);
        assertTrue(tow.internalCall());
    }

    @Test
    void followsLambdasAndMethodReferences() {
        AnalysisResult r = analyze(fromMain());
        Finding methodRef = find(r, "Garage", "serviceAll", "stop").get(0);
        assertTrue(methodRef.site().viaLambda());

        Finding lambdaBody = find(r, "Garage", "lambda$serviceAll$0", "honk").get(0);
        assertEquals(List.of("App#main(String[])", "Garage#serviceAll(List)", "Garage#lambda$serviceAll$0(ICar)"),
                chainNames(lambdaBody));
        // honk() is a default method, so every implementation runs ICar#honk
        assertTrue(lambdaBody.matchedTargets().stream().allMatch(t -> t.method().owner().equals("sample/cars/ICar")));
        // and its body (calling brand()) is analysed too
        assertEquals(1, find(r, "ICar", "honk", "brand").size());
    }

    @Test
    void ignoresClassesThatDoNotImplementTarget() {
        AnalysisResult r = analyze(fromMain());
        assertTrue(r.findings().stream().noneMatch(f -> f.site().target().owner().equals("sample/cars/Bicycle")));
        // but still walked through it
        assertTrue(r.analysedMethods().stream().anyMatch(m -> m.owner().equals("sample/cars/Bicycle")));
    }

    @Test
    void ignoresUnreachableCode() {
        AnalysisResult r = analyze(fromMain());
        assertTrue(r.findings().stream().noneMatch(f -> f.caller().owner().equals("sample/cars/Showroom")));
    }

    @Test
    void terminatesOnRecursionAndAnalysesEachMethodOnce() {
        AnalysisResult r = analyze(fromMain());
        assertEquals(1, find(r, "Mechanic", "check", "brand").size());
        assertTrue(r.analysedMethods().stream().anyMatch(m -> m.name().equals("pongPing")));
    }

    @Test
    void reportsNonInterfaceMethodsOfImplementations() {
        AnalysisResult r = analyze(fromMain());
        Finding f = find(r, "Dealer", "sell", "openSunroof").get(0);
        assertFalse(f.interfaceMethod());
        assertEquals(InvokeKind.VIRTUAL, f.site().kind());
    }

    @Test
    void marksMatchesOnlyPossibleThroughDynamicDispatch() {
        AnalysisResult r = analyze(fromMain());
        Finding f = find(r, "Garage", "describe", "toString").get(0);
        assertFalse(f.receiverTypeMatches());
        assertEquals(List.of("sample/cars/Bmw"), f.matchedTargets().stream().map(DispatchTarget::receiverClass).toList());

        AnalysisResult direct = analyze(fromMain().directOnly(true));
        assertTrue(find(direct, "Garage", "describe", "toString").isEmpty());
    }

    @Test
    void constructorsAreOptional() {
        assertTrue(analyze(fromMain()).findings().stream().noneMatch(f -> f.site().target().isConstructor()));
        AnalysisResult r = analyze(fromMain().includeConstructors(true));
        assertFalse(find(r, "App", "main", "<init>").isEmpty());
    }

    @Test
    void maxDepthLimitsDescent() {
        AnalysisResult r = analyze(fromMain().maxDepth(1));
        assertTrue(r.findings().stream().allMatch(f -> f.depth() <= 1));
        assertTrue(find(r, "Mechanic", "check", "brand").isEmpty());
        assertFalse(find(r, "Dealer", "sell", "openSunroof").isEmpty());
    }

    @Test
    void filtersInterfaceMethodsAndInternalCalls() {
        AnalysisResult onlyIface = analyze(fromMain().onlyInterfaceMethods(true));
        assertTrue(onlyIface.findings().stream().allMatch(Finding::interfaceMethod));
        assertTrue(find(onlyIface, "Dealer", "sell", "openSunroof").isEmpty());

        AnalysisResult external = analyze(fromMain().skipInternalCalls(true));
        assertTrue(external.findings().stream().noneMatch(Finding::internalCall));
        assertTrue(find(external, "Tesla", "start", "charge").isEmpty());
        assertFalse(find(external, "Garage", "service", "start").isEmpty());
    }

    @Test
    void excludeStopsDescent() {
        AnalysisResult r = analyze(fromMain().exclude("sample.cars.Mechanic"));
        assertTrue(find(r, "Mechanic", "check", "brand").isEmpty());
        assertFalse(find(r, "Diagnostics", "report", "brand").isEmpty());
    }

    @Test
    void abstractEntryStandsForItsImplementations() {
        AnalysisResult r = analyze(new callanalyzer.analysis.AnalysisOptions()
                .entry("sample.cars.ICar#start").target("sample.cars.ICar"));
        assertEquals(List.of("AbstractCar#start()", "Tesla#start()"),
                r.entries().stream().map(MethodRef::toShortString).toList());
        assertFalse(find(r, "Diagnostics", "report", "brand").isEmpty());
    }

    @Test
    void subInterfaceCanBeTheTarget() {
        AnalysisResult r = analyze(new callanalyzer.analysis.AnalysisOptions()
                .entry("sample.cars.App#main").target("sample.cars.ElectricCar"));
        Finding f = find(r, "Garage", "service", "start").get(0);
        assertFalse(f.receiverTypeMatches()); // static type is ICar, only Tesla is an ElectricCar
        assertEquals(List.of("sample/cars/Tesla"), f.matchedTargets().stream().map(DispatchTarget::receiverClass).toList());
    }

    @Test
    void unknownEntryIsReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> analyze(new callanalyzer.analysis.AnalysisOptions()
                        .entry("sample.cars.Garage#nope").target("sample.cars.ICar")));
        assertTrue(e.getMessage().contains("sample.cars.Garage#service(sample.cars.ICar)"));
    }
}
