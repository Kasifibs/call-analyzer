package callanalyzer;

import callanalyzer.analysis.AnalysisOptions;
import callanalyzer.analysis.AnalysisResult;
import callanalyzer.analysis.CallGraphAnalyzer;
import callanalyzer.analysis.ChainStep;
import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.analysis.Finding;
import callanalyzer.events.EventConfig;
import callanalyzer.events.EventDispatch;
import callanalyzer.events.EventDispatch.Resolution;
import callanalyzer.events.Registration;
import callanalyzer.model.MethodRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventRouterTest {

    private static final EventConfig EVENTS = new EventConfig("sample.events.Event", "sample.events.EventDispatcher",
            "sample.events.IObserver", null);

    private static AnalysisResult analyze(String method, boolean withEvents) {
        AnalysisOptions options = new AnalysisOptions().entry("sample.events.EventFlowService#" + method)
                .target("sample.cars.ICar");
        if (withEvents) {
            options.events(EVENTS);
        }
        return new CallGraphAnalyzer(Fixtures.hierarchy(), options, Fixtures.classBytes()).analyze();
    }

    private static List<String> observers(EventDispatch e) {
        return e.observers().stream().map(DispatchTarget::receiverClass).map(MethodRef::simpleName).toList();
    }

    private static boolean reached(AnalysisResult r, String simpleClassName) {
        return r.analysedMethods().stream().anyMatch(m -> MethodRef.simpleName(m.owner()).equals(simpleClassName));
    }

    private static Finding finding(AnalysisResult r, String callerClass, String called) {
        return r.findings().stream()
                .filter(f -> MethodRef.simpleName(f.caller().owner()).equals(callerClass))
                .filter(f -> f.site().target().name().equals(called))
                .findFirst().orElseThrow(() -> new AssertionError("no finding " + callerClass + " -> " + called));
    }

    @Test
    void registrationsAreCollectedFromObservers() {
        List<Registration> regs = analyze("rent", true).eventRegistrations();
        assertEquals(List.of("AUDIT AuditObserver", "CAR_RENTED CarRentedObserver",
                        "CAR_RENTED KafkaCarRentedObserver", "CAR_RETURNED CarReturnedObserver",
                        "PAYMENT_DONE PaymentObserver"),
                regs.stream().map(r -> r.eventName() + " " + MethodRef.simpleName(r.observerClass())).sorted().toList());
    }

    @Test
    void dispatchContinuesOnlyIntoObserversOfThatEvent() {
        AnalysisResult r = analyze("rent", true);
        EventDispatch first = r.events().get(0);
        assertEquals(Resolution.BY_NAME, first.resolution());
        assertEquals(List.of("CAR_RENTED"), first.eventNames());
        assertEquals(List.of("CarRentedObserver", "KafkaCarRentedObserver"), observers(first));
        // the bridge method handle(Event) is skipped in favour of handle(CarRentedEvent)
        assertEquals("(Lsample/events/CarRentedEvent;)V", first.observers().get(0).method().desc());
        assertFalse(reached(r, "PaymentObserver"));
        assertFalse(reached(r, "AuditObserver"));

        Finding tow = finding(r, "CarRentedObserver", "tow");
        assertEquals(List.of("event CAR_RENTED"), tow.chain().stream().map(ChainStep::via)
                .filter(v -> v != null).toList());
    }

    @Test
    void innerEventsAreFollowed() {
        AnalysisResult r = analyze("rent", true);
        EventDispatch inner = r.events().get(1);
        assertEquals("CarRentedObserver", MethodRef.simpleName(inner.caller().owner()));
        assertEquals(List.of("CarReturnedObserver"), observers(inner));
        Finding charge = finding(r, "CarReturnedObserver", "charge");
        assertEquals(List.of("event CAR_RENTED", "event CAR_RETURNED"),
                charge.chain().stream().map(ChainStep::via).filter(v -> v != null).toList());
    }

    @Test
    void withoutEventConfigEveryObserverIsReached() {
        AnalysisResult r = analyze("rent", false);
        assertTrue(r.events().isEmpty());
        assertTrue(reached(r, "PaymentObserver")); // plain class hierarchy analysis: all IObserver implementations
    }

    @Test
    void eventNameFromConstructorArgumentAtTheNewSite() {
        EventDispatch e = analyze("returnByName", true).events().get(0);
        assertEquals(List.of("CAR_RETURNED"), e.eventNames());
        assertEquals(List.of("CarReturnedObserver"), observers(e));
    }

    @Test
    void eventNameFromOverriddenGetter() {
        AnalysisResult r = analyze("pay", true);
        assertEquals(List.of("PaymentObserver"), observers(r.events().get(0)));
        finding(r, "PaymentObserver", "openSunroof");
    }

    @Test
    void eventPassedThroughHelperIsResolvedFromCallers() {
        EventDispatch e = analyze("returnViaHelper", true).events().get(0);
        assertEquals("publish", e.caller().name());
        assertEquals(List.of("CarReturnedEvent"), e.eventClasses().stream().map(MethodRef::simpleName).toList());
        assertEquals(List.of("CarReturnedObserver"), observers(e));
    }

    @Test
    void fallsBackToHandleParameterTypeWhenNameIsUnknown() {
        EventDispatch dynamic = analyze("dynamicName", true).events().get(0);
        assertEquals(Resolution.BY_TYPE, dynamic.resolution());
        assertEquals(List.of("AuditObserver"), observers(dynamic)); // only handle(Event) accepts a NamedEvent

        EventDispatch unregistered = analyze("noListener", true).events().get(0);
        assertEquals(Resolution.BY_TYPE, unregistered.resolution());
        assertEquals(List.of("NOBODY_LISTENS"), unregistered.eventNames());
    }

    @Test
    void unknownEventGoesToAllObservers() {
        EventDispatch e = analyze("unknownEvent", true).events().get(0);
        assertEquals(Resolution.ALL, e.resolution());
        assertEquals(List.of("AuditObserver", "CarRentedObserver", "CarReturnedObserver", "KafkaCarRentedObserver",
                "PaymentObserver"), observers(e));
    }
}
