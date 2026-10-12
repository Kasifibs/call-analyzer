package sample.events;

import sample.cars.Tesla;

public class SpareTyreObserver extends BaseHandler<SpareTyreEvent> {
    private Tesla tesla;

    public SpareTyreObserver(EventDispatcher dispatcher) {
        dispatcher.addObserver(this);
    }

    @Override
    protected void onEvent(SpareTyreEvent event) {
        tesla.charge();
    }
}
