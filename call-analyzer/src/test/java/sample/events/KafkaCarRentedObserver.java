package sample.events;

/** Only forwards the event to Kafka. */
public class KafkaCarRentedObserver implements IObserver<CarRentedEvent> {
    public KafkaCarRentedObserver(EventDispatcher dispatcher) {
        dispatcher.register(CarRentedEvent.NAME, this);
    }

    @Override
    public void handle(CarRentedEvent event) {
        KafkaPublisher.publish("car-rented", event.getName());
    }
}
