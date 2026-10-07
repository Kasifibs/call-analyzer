package sample.cars;

/** Has ICar-like methods but does not implement ICar: calls to it must not be reported. */
public class Bicycle {
    public void start() {
        System.out.println("pedal");
    }

    public String brand() {
        return "Bianchi";
    }
}
