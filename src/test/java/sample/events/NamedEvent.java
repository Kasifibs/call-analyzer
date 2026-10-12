package sample.events;

/** The name is given by whoever creates the event. */
public class NamedEvent extends Event {
    public NamedEvent(String name) {
        super(name);
    }
}
