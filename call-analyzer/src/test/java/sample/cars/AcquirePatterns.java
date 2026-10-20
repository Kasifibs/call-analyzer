package sample.cars;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The different ways a list handed to {@link CarAcquirer#acquire} can be built. */
public class AcquirePatterns {
    private Bmw bmw;
    private Tesla tesla;
    private final List<ICar> fleet = new ArrayList<>();

    public AcquirePatterns() {
        fleet.add(new TowTruck());
    }

    public void viaNew() {
        CarAcquirer.acquire(List.of(new Bmw(), new Tesla()));
    }

    public void viaArrayList() {
        List<ICar> cars = new ArrayList<>();
        cars.add(bmw);
        cars.add(new TowTruck());
        CarAcquirer.acquire(cars);
    }

    public void viaArraysAsList() {
        CarAcquirer.acquire(Arrays.asList(bmw, tesla));
    }

    public void viaGetter() {
        CarAcquirer.acquire(cars());
    }

    private List<ICar> cars() {
        return List.of(tesla);
    }

    public void viaParameter() {
        run(List.of(bmw));
    }

    private void run(List<ICar> cars) {
        CarAcquirer.acquire(cars);
    }

    public void viaFieldList() {
        CarAcquirer.acquire(fleet);
    }

    public void viaCopy() {
        CarAcquirer.acquire(new ArrayList<>(List.of(bmw)));
    }

    public void viaLambda() {
        Runnable inTransaction = () -> CarAcquirer.acquire(List.of(bmw));
        inTransaction.run();
    }
}
