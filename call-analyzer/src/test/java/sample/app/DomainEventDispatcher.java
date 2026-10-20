package sample.app;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DomainEventDispatcher {
    private final Map<String, List<IObserver<?>>> observers = new HashMap<>();

    public void addObserver(IObserver<?> observer) {
        observers.computeIfAbsent(observer.getClass().getName(), k -> new ArrayList<>()).add(observer);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void notify(DomainEvent event) {
        for (IObserver observer : observers.getOrDefault(event.getClass().getName(), List.of())) {
            observer.handle(event);
        }
    }
}
