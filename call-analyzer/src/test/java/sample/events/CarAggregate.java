package sample.events;

import java.util.ArrayList;
import java.util.List;

/** DDD style aggregate: collects its domain events and hands them out to be published. */
public class CarAggregate {
    private final List<Event> events = new ArrayList<>();

    public void changeTyre() {
        record(new TyreChangedEvent());
    }

    public void scrap() {
        events.add(new SpareTyreEvent()); // never called in the flows below: must not count as published
    }

    private void record(Event event) {
        events.add(event);
    }

    public List<Event> pullEvents() {
        return events;
    }
}
