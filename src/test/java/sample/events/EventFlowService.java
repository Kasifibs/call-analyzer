package sample.events;

import sample.cars.Bmw;

public class EventFlowService {
    private EventDispatcher dispatcher;
    private Bmw bmw;

    public void rent() {
        bmw.openSunroof();
        dispatcher.dispatch(new CarRentedEvent(bmw));
    }

    public void returnByName() {
        dispatcher.dispatch(new NamedEvent("CAR_RETURNED"));
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
}
