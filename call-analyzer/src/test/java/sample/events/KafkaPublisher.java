package sample.events;

public final class KafkaPublisher {
    private KafkaPublisher() {
    }

    public static void publish(String topic, String payload) {
        System.out.println("kafka " + topic + ": " + payload);
    }
}
