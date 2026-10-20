package sample.events;

import sample.cars.TowTruck;

/** Registers without an event name, like {@code domainEventDispatcher.addObserver(this)}. */
public class TyreObserver implements IObserver<TyreChangedEvent> {
    private TowTruck towTruck;

    public TyreObserver(EventDispatcher dispatcher) {
        dispatcher.addObserver(this);
    }

    @Override
    public void handle(TyreChangedEvent event) {
        towTruck.brand();
    }
}
