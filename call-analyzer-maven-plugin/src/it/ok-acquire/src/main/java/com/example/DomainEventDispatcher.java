package com.example;

import java.util.ArrayList;
import java.util.List;

public class DomainEventDispatcher {
    private final List<IObserver<?>> observers = new ArrayList<>();

    public void addObserver(IObserver<?> observer) {
        observers.add(observer);
    }

    public void notify(DomainEvent event) {
    }
}
