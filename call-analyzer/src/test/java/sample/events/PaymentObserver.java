package sample.events;

import sample.cars.Bmw;

/** Listens to an unrelated event: must not be reached from car rental flows. */
public class PaymentObserver implements IObserver<PaymentEvent> {
    private Bmw bmw;

    public PaymentObserver(EventDispatcher dispatcher) {
        dispatcher.register("PAYMENT_DONE", this);
    }

    @Override
    public void handle(PaymentEvent event) {
        bmw.openSunroof();
    }
}
