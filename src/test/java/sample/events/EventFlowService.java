package sample.events;

import sample.cars.Bmw;

public class EventFlowService {
    private EventDispatcher dispatcher;
    private Bmw bmw;
    private final CarAggregate aggregate = new CarAggregate();

    public void rent() {
        bmw.openSunroof();
        dispatcher.dispatch(new CarRentedEvent(bmw));
    }

    public void maintenance() {
        dispatcher.dispatch(new NamedEvent("MAINTENANCE"));
    }

    public void pay() {
        dispatcher.dispatch(new PaymentEvent());
    }

    public void returnViaHelper() {
        publish(new CarReturnedEvent());
    }

    private void publish(Event event) {
        dispatcher.dispatch(event);
    }

    public void unknownEvent(Event event) {
        dispatcher.dispatch(event);
    }

    public void dynamicName() {
        dispatcher.dispatch(new NamedEvent(String.valueOf(System.nanoTime())));
    }

    public void noListener() {
        dispatcher.dispatch(new NamedEvent("NOBODY_LISTENS"));
    }

    public void changeTyre() {
        dispatcher.dispatch(new TyreChangedEvent());
    }

    public void urgentTyre() {
        dispatcher.dispatch(new UrgentTyreChangedEvent());
    }

    public void spareTyre() {
        dispatcher.dispatch(new SpareTyreEvent());
    }

    public void aggregateLoop() {
        aggregate.changeTyre();
        for (Event event : aggregate.pullEvents()) {
            dispatcher.dispatch(event);
        }
    }

    public void aggregateMethodRef() {
        aggregate.changeTyre();
        aggregate.pullEvents().forEach(dispatcher::dispatch);
    }

    public void aggregateLambda() {
        aggregate.changeTyre();
        aggregate.pullEvents().stream().filter(e -> e != null).forEach(e -> dispatcher.dispatch(e));
    }

    public void createWithoutPublishing() {
        TyreChangedEvent event = new TyreChangedEvent(); // created, but never handed to the dispatcher
        System.out.println(event);
    }
}
