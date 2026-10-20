package com.example;

import java.util.List;

public class RentalService {
    private Bmw bmw;
    private Tesla tesla;
    private DomainEventDispatcher dispatcher;

    public void rent() {
        CarAcquirer.acquire(List.of(bmw));
        bmw.start();
        dispatcher.notify(new CarRented());
    }

    @AcquireCar(cars = Tesla.class)
    public void charge() {
        tesla.start();
    }
}
