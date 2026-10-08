package sample.cars;

import java.util.List;

/** Mimics a Spring service whose cars are injected into concretely typed fields ({@code @Autowired Bmw bmw}). */
public class RentalService {
    private Bmw bmw;
    private Tesla tesla;
    private TowTruck towTruck;
    private ICar spare;
    private final Garage garage = new Garage();

    public void rent() {
        CarAcquirer.acquire(List.of(bmw, tesla));
        bmw.openSunroof();
        tesla.charge();
        towTruck.tow(bmw); // TowTruck is used but not acquired
    }

    public void rentOnlyBmw() {
        CarAcquirer.acquire(List.of(bmw));
        garage.service(bmw); // goes through ICar: Tesla/TowTruck are only possible receivers
    }

    public void rentWithSpare() {
        CarAcquirer.acquire(List.of(bmw, spare)); // spare is typed as ICar: its class is unknown
        tesla.charge();
    }

    public void rentTooMuch() {
        CarAcquirer.acquire(List.of(bmw, tesla));
        bmw.openSunroof(); // tesla acquired but never used
    }

    @AcquireCar(cars = {Bmw.class, TowTruck.class})
    public void rentAnnotated() {
        bmw.openSunroof();
        tesla.charge(); // Tesla is used but not in the annotation
        towTruck.tow(bmw);
    }

    @AcquireCar(cars = Tesla.class)
    public void rentAnnotatedAndAcquired() {
        CarAcquirer.acquire(List.of(bmw));
        bmw.openSunroof();
        tesla.charge();
        towTruck.tow(bmw); // TowTruck is neither in the annotation nor in the list
    }

    @LegacyAcquireCar({Bmw.class, Bicycle.class})
    public void rentLegacyAnnotated() {
        bmw.openSunroof();
    }
}
