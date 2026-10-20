package callanalyzer.events;

/**
 * Where the application's event mechanism lives. All names are fully qualified class names as the user writes them
 * ({@code com.example.event.EventDispatcher}).
 *
 * @param eventClass      base class (or interface) of all events
 * @param dispatcherClass class through which events are published and observers register
 * @param observerClass   base type of all observers ({@code IObserver})
 * @param handleMethod    name of the observer method that receives an event (usually {@code handle})
 * @param exactTypeMatch  for observers registered without an event name ({@code addObserver(this)}): {@code true}
 *                        if an {@code IObserver<AEvent>} only receives {@code AEvent} itself, {@code false} if it also
 *                        receives subclasses of {@code AEvent}
 * @param detection       how published events are found
 */
public record EventConfig(String eventClass, String dispatcherClass, String observerClass, String handleMethod,
                          boolean exactTypeMatch, Detection detection) {

    public enum Detection {
        /**
         * Every event created with {@code new} in the flow is assumed to be published; its observers continue the
         * flow from where it is created. Dispatcher calls are only used to confirm the publication.
         */
        NEW,
        /** Events are those passed to the dispatcher's publish method ({@code notify(event)}). */
        DISPATCH
    }

    public EventConfig(String eventClass, String dispatcherClass, String observerClass, String handleMethod) {
        this(eventClass, dispatcherClass, observerClass, handleMethod, true, Detection.NEW);
    }

    public EventConfig(String eventClass, String dispatcherClass, String observerClass, String handleMethod,
                       boolean exactTypeMatch) {
        this(eventClass, dispatcherClass, observerClass, handleMethod, exactTypeMatch, Detection.NEW);
    }

    public EventConfig {
        if (eventClass == null || dispatcherClass == null || observerClass == null) {
            throw new IllegalArgumentException(
                    "Event takibi icin --event-class, --event-dispatcher ve --event-observer birlikte verilmeli.");
        }
        if (handleMethod == null || handleMethod.isBlank()) {
            handleMethod = "handle";
        }
        if (detection == null) {
            detection = Detection.NEW;
        }
    }
}
