package sample.events;

/** The name comes from an overridden getter instead of the constructor. */
public class PaymentEvent extends Event {
    public PaymentEvent() {
        super(null);
    }

    @Override
    public String getName() {
        return "PAYMENT_DONE";
    }
}
