package sample.app;

public interface IObserver<T extends DomainEvent> {
    void handle(T event);
}
