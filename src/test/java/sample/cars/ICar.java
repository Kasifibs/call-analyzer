package sample.cars;

public interface ICar {
    void start();

    void stop();

    String brand();

    default void honk() {
        System.out.println(brand() + ": beep");
    }
}
