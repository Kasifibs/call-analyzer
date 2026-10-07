package sample.cars;

import java.util.List;

public class Garage {
    public void service(ICar car) {
        inspect(car);
        car.start();
    }

    private void inspect(ICar car) {
        Mechanic.check(car, 3);
    }

    public void serviceAll(List<ICar> cars) {
        cars.forEach(ICar::stop);
        cars.forEach(c -> c.honk());
    }

    public void describe(Object o) {
        System.out.println(o.toString());
    }
}
