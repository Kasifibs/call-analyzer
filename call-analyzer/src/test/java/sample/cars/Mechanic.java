package sample.cars;

public final class Mechanic {
    private Mechanic() {
    }

    public static void check(ICar car, int round) {
        String b = car.brand();
        if (round > 0 && b != null) {
            check(car, round - 1); // recursion must not loop forever
        }
    }
}
