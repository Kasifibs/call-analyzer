package sample.events;

import sample.cars.ICar;

public class CarRentedEvent extends Event {
    public static final String NAME = "CAR_RENTED";
    private final ICar car;

    public CarRentedEvent(ICar car) {
        super(NAME);
        this.car = car;
    }

    public ICar getCar() {
        return car;
    }
}
