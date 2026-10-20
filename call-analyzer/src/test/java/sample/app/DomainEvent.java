package sample.app;

public abstract class DomainEvent {
    private final String type;
    private final String data;

    protected DomainEvent(String type, String data) {
        this.type = type;
        this.data = data;
    }

    public String getType() {
        return type;
    }
}
