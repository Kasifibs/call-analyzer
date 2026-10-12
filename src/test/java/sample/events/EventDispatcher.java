package sample.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EventDispatcher {
    private final Map<String, List<IObserver<? extends Event>>> observers = new HashMap<>();

    public void register(String eventName, IObserver<? extends Event> observer) {
        observers.computeIfAbsent(eventName, k -> new ArrayList<>()).add(observer);
    }

    /** Registration without a name: the dispatcher keys the observer by the event type it handles. */
    public void addObserver(IObserver<? extends Event> observer) {
        observers.computeIfAbsent(observer.getClass().getName(), k -> new ArrayList<>()).add(observer);
    }

    public void unregister(String eventName, IObserver<? extends Event> observer) {
        observers.getOrDefault(eventName, new ArrayList<>()).remove(observer);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void dispatch(Event event) {
        for (IObserver observer : observers.getOrDefault(event.getName(), List.of())) {
            observer.handle(event);
        }
    }
}
