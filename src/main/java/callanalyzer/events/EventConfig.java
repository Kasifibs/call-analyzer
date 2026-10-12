package callanalyzer.events;

/**
 * Where the application's event mechanism lives. All names are fully qualified class names as the user writes them
 * ({@code com.example.event.EventDispatcher}).
 *
 * @param eventClass      base class (or interface) of all events
 * @param dispatcherClass class through which events are published and observers register
 * @param observerClass   base type of all observers ({@code IObserver})
 * @param handleMethod    name of the observer method that receives an event (usually {@code handle})
 */
public record EventConfig(String eventClass, String dispatcherClass, String observerClass, String handleMethod) {

    public EventConfig {
        if (eventClass == null || dispatcherClass == null || observerClass == null) {
            throw new IllegalArgumentException(
                    "Event takibi icin --event-class, --event-dispatcher ve --event-observer birlikte verilmeli.");
        }
        if (handleMethod == null || handleMethod.isBlank()) {
            handleMethod = "handle";
        }
    }
}
