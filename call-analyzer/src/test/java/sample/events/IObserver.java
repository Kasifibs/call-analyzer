package sample.events;

public interface IObserver<T extends Event> {
    void handle(T event);
}
