package sample.events;

import sample.cars.Tesla;

/** Registers in an init method (like @PostConstruct) instead of the constructor. */
public class CarReturnedObserver implements IObserver<CarReturnedEvent> {
    private Tesla tesla;

    public void init(EventDispatcher dispatcher) {
        dispatcher.register("CAR_RETURNED", this);
    }

    @Override
    public void handle(CarReturnedEvent event) {
        tesla.charge();
    }
}
