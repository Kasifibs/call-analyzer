package com.example;

public interface IObserver<T extends DomainEvent> {
    void handle(T event);
}
