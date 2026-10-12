package sample.events;

/** Generic base handler: handle(T) is erased to handle(Event), the real type is only in the subclass signature. */
public abstract class BaseHandler<T extends Event> implements IObserver<T> {
    @Override
    public void handle(T event) {
        onEvent(event);
    }

    protected abstract void onEvent(T event);
}
