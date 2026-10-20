package com.example;

public class CarRentedHandler implements IObserver<CarRented> {
    private Bmw bmw;

    public CarRentedHandler(DomainEventDispatcher dispatcher) {
        dispatcher.addObserver(this);
    }

    @Override
    public void handle(CarRented event) {
        bmw.start(); // Bmw is acquired by RentalService#rent
    }
}
