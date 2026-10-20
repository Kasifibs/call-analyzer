package callanalyzer.events;

import callanalyzer.analysis.ClassHierarchy.DispatchTarget;
import callanalyzer.model.MethodRef;

import java.util.List;
import java.util.Map;

/**
 * An event published in the analysed flow, and the observers the flow continues into.
 *
 * @param caller         method publishing the event
 * @param sourceFile     source file of the caller
 * @param line           line of the dispatch call
 * @param dispatchMethod the dispatcher method called
 * @param eventClasses   classes the published event can be (internal names)
 * @param eventNames     event names the published event can have
 * @param eventOrigins   per event class (internal name, {@code "?"} for objects whose class is unknown): where the
 *                       published objects come from, e.g. {@code new BDomainEvent() (Car.java:33)}
 * @param resolution     how the observers were selected
 * @param observers      the observers' handle methods the flow continues into
 */
public record EventDispatch(MethodRef caller, String sourceFile, int line, MethodRef dispatchMethod,
                            List<String> eventClasses, List<String> eventNames, Map<String, List<String>> eventOrigins,
                            Resolution resolution,
                            List<DispatchTarget> observers) {

    public enum Resolution {
        /** The event name is known: observers registered for that name. */
        BY_NAME,
        /** The name is unknown but the event class is: observers whose {@code handle} accepts that class. */
        BY_TYPE,
        /** Nothing is known about the event: every observer. */
        ALL
    }

    /** Short description used in call chains, e.g. {@code event CAR_RENTED}. */
    public String label() {
        if (!eventNames.isEmpty() && resolution == Resolution.BY_NAME) {
            return "event " + String.join("|", eventNames);
        }
        if (!eventClasses.isEmpty()) {
            return "event " + String.join("|", eventClasses.stream().map(MethodRef::simpleName).toList());
        }
        return "event ?";
    }
}
