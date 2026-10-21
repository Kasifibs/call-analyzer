package callanalyzer;

import callanalyzer.acquire.AcquireExemption;
import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.events.EventConfig;
import callanalyzer.model.MethodRef;
import callanalyzer.scan.AcquireScanner;
import callanalyzer.scan.Candidate;
import callanalyzer.scan.ScanItem;
import callanalyzer.scan.ScanItem.Status;
import callanalyzer.scan.ScanResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcquireScannerTest {

    private static AcquireScanner scanner(AnalysisOptions options) {
        return new AcquireScanner(Fixtures.hierarchy(), Fixtures.classBytes(), options,
                "sample.cars.CarAcquirer#acquire", "sample.cars.AcquireCar");
    }

    private static AnalysisOptions options() {
        return new AnalysisOptions().target("sample.cars.ICar").include("sample");
    }

    private static List<String> names(List<Candidate> candidates) {
        return candidates.stream().map(c -> MethodRef.simpleName(c.method().owner()) + "#" + c.method().name()).toList();
    }

    private static ScanItem item(ScanResult r, String method) {
        return r.items().stream().filter(i -> i.candidate().method().name().equals(method)).findFirst().orElseThrow();
    }

    @Test
    void findsMethodsCallingTheAcquirerOrCarryingTheAnnotation() {
        List<Candidate> candidates = scanner(options()).candidates();
        List<String> names = names(candidates);
        assertTrue(names.containsAll(List.of("RentalService#rent", "RentalService#rentAnnotated",
                "RentalService#rentWithHelperAcquire", "RentalService#acquireTesla", "AcquirePatterns#run",
                "AcquirePatterns#viaNew")), names.toString());
        assertFalse(names.contains("CarAcquirer#acquire"), "the acquirer itself is not a candidate");
        assertFalse(names.contains("RentalService#rentLegacyAnnotated"), "other annotations do not count");
        assertTrue(names.stream().noneMatch(n -> n.contains("lambda$")), names.toString());

        Candidate both = candidates.stream().filter(c -> c.method().name().equals("rentAnnotatedAndAcquired"))
                .findFirst().orElseThrow();
        assertTrue(both.callsAcquirer() && both.annotated());
    }

    @Test
    void anAcquireInsideALambdaBelongsToTheMethodDefiningIt() {
        AcquireScanner scanner = scanner(options());
        Candidate viaLambda = scanner.candidates().stream().filter(c -> c.method().name().equals("viaLambda"))
                .findFirst().orElseThrow();
        assertTrue(viaLambda.callsAcquirer());
        ScanResult r = scanner.run(List.of(viaLambda), s -> { }, (i, a) -> { });
        assertEquals(List.of("sample/cars/Bmw"), r.items().get(0).comparison().acquired());
        assertTrue(r.items().get(0).comparison().otherCalls().isEmpty());
    }

    @Test
    void analysesEveryCandidateWithItsOwnStatus() {
        AcquireScanner scanner = scanner(options());
        List<String> seen = new ArrayList<>();
        ScanResult r = scanner.run(scanner.candidates(), s -> { },
                (i, analysis) -> seen.add(analysis.entries().get(0).name()));
        assertEquals(r.items().size(), seen.size());
        assertEquals(Status.MISSING, item(r, "rent").status());
        assertEquals(List.of("sample/cars/TowTruck"),
                item(r, "rent").comparison().missing().stream().map(u -> u.className()).toList());
        assertEquals(Status.MISSING, item(r, "rentAnnotated").status());
        assertEquals(Status.POSSIBLE, item(r, "rentOnlyBmw").status());
        assertEquals(Status.OK, item(r, "viaNew").status());
        // the list comes from outside: its content is unknown, not empty
        assertFalse(item(r, "run").comparison().unresolved().isEmpty());
    }

    @Test
    void exemptionsApplyToTheirMethodOnly() {
        AcquireScanner scanner = scanner(options()).withExemptions(
                List.of(AcquireExemption.parse("sample.cars.RentalService#rent=sample.cars.TowTruck")));
        ScanResult r = scanner.run(scanner.candidates(), s -> { }, (i, a) -> { });
        assertEquals(Status.OK, item(r, "rent").status());
        assertEquals(List.of("sample/cars/TowTruck"),
                item(r, "rent").comparison().exempt().stream().map(u -> u.className()).toList());
        assertEquals(Status.MISSING, item(r, "rentAnnotated").status());
    }

    @Test
    void includeFilterLimitsTheScan() {
        assertTrue(scanner(new AnalysisOptions().target("sample.cars.ICar").include("sample.events"))
                .candidates().isEmpty());
    }

    @Test
    void eventRoutingIsSharedAcrossTheScan() {
        AnalysisOptions options = options().events(new EventConfig("sample.events.Event",
                "sample.events.EventDispatcher", "sample.events.IObserver", null));
        AcquireScanner scanner = scanner(options);
        ScanResult r = scanner.run(scanner.candidates(), s -> { }, (i, a) -> { });
        assertTrue(r.items().stream().allMatch(i -> i.error() == null));
    }
}
