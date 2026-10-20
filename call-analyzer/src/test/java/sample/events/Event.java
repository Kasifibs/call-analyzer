package sample.events;

public abstract class Event {
    private final String name;

    protected Event(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
