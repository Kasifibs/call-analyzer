package sample.events;

public class MaintenanceObserver implements IObserver<NamedEvent> {
    public MaintenanceObserver(EventDispatcher dispatcher) {
        dispatcher.register("MAINTENANCE", this);
    }

    @Override
    public void handle(NamedEvent event) {
        System.out.println("maintenance " + event.getName());
    }
}
