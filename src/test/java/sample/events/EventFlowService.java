package sample.events;

import sample.cars.Bmw;

public class EventFlowService {
    private EventDispatcher dispatcher;
    private Bmw bmw;

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
}
