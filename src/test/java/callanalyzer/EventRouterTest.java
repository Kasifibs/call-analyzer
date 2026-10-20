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
                        "MAINTENANCE MaintenanceObserver", "PAYMENT_DONE PaymentObserver",
                        "tip:SpareTyreEvent SpareTyreObserver", "tip:TyreChangedEvent TyreObserver"),
                regs.stream().map(r -> (r.eventName() != null ? r.eventName() : "tip:" + MethodRef.simpleName(r.eventType()))
                        + " " + MethodRef.simpleName(r.observerClass())).sorted().toList());
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
        EventDispatch e = analyze("maintenance", true).events().get(0);
        assertEquals(List.of("MAINTENANCE"), e.eventNames());
        assertEquals(List.of("MaintenanceObserver"), observers(e));
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
    void unknownNameIsDecidedByTheHandleParameterType() {
        EventDispatch dynamic = analyze("dynamicName", true).events().get(0);
        assertEquals(Resolution.BY_TYPE, dynamic.resolution());
        // only observers whose handle accepts a NamedEvent: handle(Event) and handle(NamedEvent)
        assertEquals(List.of("AuditObserver", "MaintenanceObserver"), observers(dynamic));

        EventDispatch unregistered = analyze("noListener", true).events().get(0);
        assertEquals(List.of("NOBODY_LISTENS"), unregistered.eventNames());
        assertEquals(List.of(), observers(unregistered));
    }

    @Test
    void observersRegisteredWithoutANameAreMatchedByTheirEventType() {
        EventDispatch tyre = analyze("changeTyre", true).events().get(0);
        assertEquals(Resolution.BY_TYPE, tyre.resolution());
        assertEquals(List.of("TyreObserver"), observers(tyre));

        // the type comes from the generic signature: SpareTyreObserver extends BaseHandler<SpareTyreEvent>
        assertEquals(List.of("SpareTyreObserver"), observers(analyze("spareTyre", true).events().get(0)));
    }

    @Test
    void observersRegisteredWithoutANameDoNotLeakIntoOtherFlows() {
        AnalysisResult r = analyze("rent", true);
        assertFalse(reached(r, "TyreObserver"));
        assertFalse(reached(r, "SpareTyreObserver"));
        assertTrue(r.events().stream().allMatch(e -> e.observers().stream()
                .noneMatch(t -> t.receiverClass().contains("Tyre"))));
    }

    @Test
    void exactOrAssignableTypeMatching() {
        assertEquals(List.of(), observers(analyze("urgentTyre", true).events().get(0)));
        EventConfig assignable = new EventConfig("sample.events.Event", "sample.events.EventDispatcher",
                "sample.events.IObserver", null, false);
        AnalysisResult r = new CallGraphAnalyzer(Fixtures.hierarchy(), new AnalysisOptions()
                .entry("sample.events.EventFlowService#urgentTyre").target("sample.cars.ICar").events(assignable),
                Fixtures.classBytes()).analyze();
        assertEquals(List.of("TyreObserver"), observers(r.events().get(0)));
    }

    @Test
    void unknownEventGoesToAllObservers() {
        EventDispatch e = analyze("unknownEvent", true).events().get(0);
        assertEquals(Resolution.ALL, e.resolution());
        assertEquals(List.of("AuditObserver", "CarRentedObserver", "CarReturnedObserver", "KafkaCarRentedObserver",
                "MaintenanceObserver", "PaymentObserver", "SpareTyreObserver", "TyreObserver"), observers(e));
    }

    @Test
    void eventsCollectedByAnAggregateOnlyCountWhenAddedInTheFlow() {
        AnalysisResult r = analyze("aggregateLoop", true);
        EventDispatch e = r.events().get(0);
        // changeTyre() runs in the flow (through the record(..) helper), scrap() does not
        assertEquals(List.of("TyreChangedEvent"), e.eventClasses().stream().map(MethodRef::simpleName).toList());
        assertEquals(List.of("TyreObserver"), observers(e));
        assertEquals(List.of("new TyreChangedEvent() (CarAggregate.java:11)"),
                e.eventOrigins().get("sample/events/TyreChangedEvent"));
        assertFalse(reached(r, "SpareTyreObserver"));
    }

    @Test
    void methodReferenceAndLambdaDispatchUseTheElementsOfTheList() {
        for (String method : List.of("aggregateMethodRef", "aggregateLambda")) {
            AnalysisResult r = analyze(method, true);
            EventDispatch e = r.events().get(0);
            assertEquals(Resolution.BY_TYPE, e.resolution(), method);
            assertEquals(List.of("TyreObserver"), observers(e), method);
            assertFalse(reached(r, "SpareTyreObserver"), method);
        }
    }
}
