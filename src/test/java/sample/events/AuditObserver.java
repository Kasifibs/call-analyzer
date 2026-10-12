package sample.events;

/** Listens to every event (handle takes the base Event). Registered for "AUDIT" only. */
public class AuditObserver implements IObserver<Event> {
    public AuditObserver(EventDispatcher dispatcher) {
        dispatcher.register("AUDIT", this);
    }

    @Override
    public void handle(Event event) {
        System.out.println("audit " + event.getName());
    }
}
