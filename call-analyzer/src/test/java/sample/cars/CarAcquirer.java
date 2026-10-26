package sample.cars;

import java.util.List;

public final class CarAcquirer {
    private CarAcquirer() {
    }

    public static void acquire(List<? extends ICar> cars) {
        for (ICar car : cars) {
            System.out.println("acquired " + car.brand()); // calls inside the acquirer must not count as usage
        }
    }

    public static void release(List<? extends ICar> cars) {
        for (ICar car : cars) {
            System.out.println("released " + car.brand()); // nor calls inside the releaser
        }
    }
}
