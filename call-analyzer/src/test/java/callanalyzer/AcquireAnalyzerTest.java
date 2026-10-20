package callanalyzer;

import callanalyzer.acquire.AcquireAnalyzer;
import callanalyzer.acquire.AcquireComparison;
import callanalyzer.acquire.AcquiredCar;
import callanalyzer.acquire.AnnotationCheck;
import callanalyzer.acquire.ClassUsage;
import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static callanalyzer.Fixtures.analyze;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AcquireAnalyzerTest {

    private static AcquireComparison compare(String entry) {
        AnalysisResult result = analyze(new AnalysisOptions().entry(entry).target("sample.cars.ICar"));
        return new AcquireAnalyzer(result, Fixtures.classBytes(), "sample.cars.CarAcquirer#acquire").analyze();
    }

    private static AcquireComparison compare(String entry, String acquirer, String annotation) {
        AnalysisResult result = analyze(new AnalysisOptions().entry(entry).target("sample.cars.ICar"));
        return new AcquireAnalyzer(result, Fixtures.classBytes(), acquirer, annotation).analyze();
    }

    private static List<String> names(List<ClassUsage> usages) {
        return usages.stream().map(u -> u.className().substring("sample/cars/".length())).toList();
    }

    private static List<String> acquired(AcquireComparison c) {
        return c.acquired().stream().map(a -> a.substring("sample/cars/".length())).toList();
    }

    @Test
    void concreteFieldsAreResolvedAndMissingClassIsReported() {
        AcquireComparison c = compare("sample.cars.RentalService#rent");
        assertEquals(1, c.calls().size());
        assertEquals(14, c.calls().get(0).line());
        assertEquals(List.of("alan RentalService.bmw : Bmw", "alan RentalService.tesla : Tesla"),
                c.calls().get(0).cars().stream().map(AcquiredCar::origin).toList());
        assertEquals(List.of("Bmw", "Tesla"), acquired(c));
        assertEquals(List.of("Bmw", "Tesla"), names(c.covered()));
        assertEquals(List.of("TowTruck"), names(c.missing()));
        ClassUsage towTruck = c.missing().get(0);
        assertEquals("tow", towTruck.certain().get(0).site().target().name());
        assertTrue(c.possiblyMissing().isEmpty());
        assertTrue(c.unused().isEmpty());
    }

    @Test
    void interfaceCallsOnlyMakeClassesPossiblyMissing() {
        AcquireComparison c = compare("sample.cars.RentalService#rentOnlyBmw");
        assertEquals(List.of("Bmw"), names(c.covered()));
        assertTrue(c.missing().isEmpty());
        assertEquals(List.of("Tesla", "TowTruck"), names(c.possiblyMissing()));
    }

    @Test
    void callsInsideTheAcquirerAreIgnored() {
        AcquireComparison c = compare("sample.cars.RentalService#rentTooMuch");
        // CarAcquirer.acquire calls car.brand() on every ICar: that must not mark anything as used
        assertEquals(List.of("Bmw"), names(c.covered()));
        assertTrue(c.possiblyMissing().isEmpty());
        assertEquals(List.of("sample/cars/Tesla"), c.unused());
    }

    @Test
    void interfaceTypedElementMakesComparisonUndecided() {
        AcquireComparison c = compare("sample.cars.RentalService#rentWithSpare");
        assertEquals(1, c.unresolved().size());
        assertEquals("sample/cars/ICar", c.unresolved().get(0).declaredType());
        assertEquals(List.of("Tesla"), names(c.undecided()));
        assertTrue(c.missing().isEmpty());
    }

    @Test
    void recognisesTheCommonWaysOfBuildingTheList() {
        assertEquals(List.of("Bmw", "Tesla"), acquired(compare("sample.cars.AcquirePatterns#viaNew")));
        assertEquals(List.of("Bmw", "TowTruck"), acquired(compare("sample.cars.AcquirePatterns#viaArrayList")));
        assertEquals(List.of("Bmw", "Tesla"), acquired(compare("sample.cars.AcquirePatterns#viaArraysAsList")));
        assertEquals(List.of("Tesla"), acquired(compare("sample.cars.AcquirePatterns#viaGetter")));
        // the acquire call is in run(), not in the entry method: resolved, but reported only for information
        AcquireComparison viaParameter = compare("sample.cars.AcquirePatterns#viaParameter");
        assertTrue(viaParameter.acquired().isEmpty());
        assertEquals(List.of("sample/cars/Bmw"), viaParameter.otherCalls().get(0).cars().stream()
                .map(AcquiredCar::concreteClass).toList());
        assertEquals(List.of("TowTruck"), acquired(compare("sample.cars.AcquirePatterns#viaFieldList")));
        assertEquals(List.of("Bmw"), acquired(compare("sample.cars.AcquirePatterns#viaCopy")));
    }

    @Test
    void noAcquireCallMeansEverythingUsedIsMissing() {
        AcquireComparison c = compare("sample.cars.Dealer#sell");
        assertTrue(c.calls().isEmpty());
        assertTrue(names(c.missing()).contains("Bmw"));
    }

    @Test
    void annotationClassesAreListedAndCompared() {
        AcquireComparison c = compare("sample.cars.RentalService#rentAnnotated", null, "sample.cars.AcquireCar");
        assertEquals(1, c.annotations().size());
        AnnotationCheck a = c.annotations().get(0);
        assertTrue(a.present());
        assertEquals(List.of("sample/cars/Bmw", "sample/cars/TowTruck"),
                a.cars().stream().map(AcquiredCar::concreteClass).toList());
        assertTrue(c.calls().isEmpty());
        assertEquals(List.of("Bmw", "TowTruck"), acquired(c));
        assertEquals(List.of("Tesla"), names(c.missing()));
        assertEquals(List.of("Bmw", "TowTruck"), names(c.covered()));
    }

    @Test
    void annotationAndAcquirerCallAreCombined() {
        AcquireComparison c = compare("sample.cars.RentalService#rentAnnotatedAndAcquired",
                "sample.cars.CarAcquirer#acquire", "sample.cars.AcquireCar");
        assertEquals(List.of("Bmw", "Tesla"), acquired(c)); // Bmw from the call, Tesla from the annotation
        assertEquals(List.of("TowTruck"), names(c.missing()));
    }

    @Test
    void missingAnnotationIsReported() {
        AcquireComparison c = compare("sample.cars.RentalService#rent", null, "sample.cars.AcquireCar");
        assertEquals(1, c.annotations().size());
        assertFalse(c.annotations().get(0).present());
        assertTrue(c.acquired().isEmpty());
        assertEquals(List.of("Bmw", "Tesla", "TowTruck"), names(c.missing()));
    }

    @Test
    void classRetentionAnnotationIsFoundAndNonTargetClassesAreFlagged() {
        AcquireComparison c = compare("sample.cars.RentalService#rentLegacyAnnotated", null,
                "sample.cars.LegacyAcquireCar");
        AnnotationCheck a = c.annotations().get(0);
        assertTrue(a.present());
        assertEquals(2, a.cars().size());
        assertTrue(a.cars().get(1).origin().contains("hedef tipi uygulamiyor"));
        assertEquals(List.of("Bmw"), names(c.covered()));
    }

    @Test
    void onlyTheEntryMethodsOwnAcquireCallsCount() {
        AcquireComparison c = compare("sample.cars.RentalService#rentWithHelperAcquire",
                "sample.cars.CarAcquirer#acquire", "sample.cars.AcquireCar");
        assertTrue(c.calls().isEmpty());
        assertEquals(1, c.otherCalls().size());
        assertEquals("acquireTesla", c.otherCalls().get(0).caller().name());
        assertEquals(List.of("Bmw"), acquired(c)); // only the annotation of the entry method
        assertEquals(List.of("Tesla"), names(c.missing()));
    }
}
