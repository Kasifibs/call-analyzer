package sample.cars;

import java.util.List;

/** Acquire calls followed (or not) by a release, for the release check. */
public class LockingService {
    private Bmw bmw;
    private Tesla tesla;

    public void releasedInFinally() {
        CarAcquirer.acquire(List.of(bmw, tesla));
        try {
            bmw.start();
            tesla.start();
        } finally {
            CarAcquirer.release(List.of(bmw, tesla));
        }
    }

    public void teslaNotReleased() {
        CarAcquirer.acquire(List.of(bmw, tesla));
        try {
            bmw.start();
            tesla.start();
        } finally {
            CarAcquirer.release(List.of(bmw));
        }
    }

    public void releasedOutsideFinally() {
        List<ICar> cars = List.of(bmw);
        CarAcquirer.acquire(cars);
        bmw.start();
        CarAcquirer.release(cars);
    }

    public void neverReleased() {
        CarAcquirer.acquire(List.of(bmw));
        bmw.start();
    }

    public void releasedInHelper() {
        CarAcquirer.acquire(List.of(bmw));
        try {
            bmw.start();
        } finally {
            unlock();
        }
    }

    private void unlock() {
        CarAcquirer.release(List.of(bmw));
    }

    public void sameListFromOutside(List<ICar> cars) {
        CarAcquirer.acquire(cars);
        try {
            cars.forEach(ICar::start);
        } finally {
            CarAcquirer.release(cars);
        }
    }

    public void acquiredInsideTry() {
        try {
            CarAcquirer.acquire(List.of(tesla));
            tesla.start();
        } finally {
            CarAcquirer.release(List.of(tesla));
        }
    }

    @AcquireCar(cars = Bmw.class)
    public void annotatedOnly() {
        bmw.start();
    }
}
