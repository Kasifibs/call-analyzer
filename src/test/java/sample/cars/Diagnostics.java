package sample.cars;

public final class Diagnostics {
    private Diagnostics() {
    }

    public static void report(ICar car) {
        System.out.println("diagnostics for " + car.brand());
    }
}
