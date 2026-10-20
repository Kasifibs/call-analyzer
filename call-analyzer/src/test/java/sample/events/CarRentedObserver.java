package sample.events;

import sample.cars.TowTruck;

/** Continues the flow in the same thread and publishes an inner event. */
public class CarRentedObserver implements IObserver<CarRentedEvent> {
    private final EventDispatcher dispatcher;
    private TowTruck towTruck;

    public CarRentedObserver(EventDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        dispatcher.register(CarRentedEvent.NAME, this);
    }

    @Override
    public void handle(CarRentedEvent event) {
        towTruck.tow(event.getCar());
        dispatcher.dispatch(new CarReturnedEvent()); // inner event
    }
}
