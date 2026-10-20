package sample.app;

import java.util.Optional;

public class EventEmitterService {

    private final DomainEventDispatcher domainEventDispatcher;

    public EventEmitterService(DomainEventDispatcher domainEventDispatcher) {
        this.domainEventDispatcher = domainEventDispatcher;
    }

    public void handle(){
        DomainEvent domainEvent = new A_DomainEvent("Type_A", "asd");
        domainEventDispatcher.notify(domainEvent);

        B_DomainEvent b_event = new B_DomainEvent("Type_B", "asd");
        domainEventDispatcher.notify(b_event);
    }
}
