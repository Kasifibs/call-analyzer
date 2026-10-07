package sample.cars;

public class TowTruck extends AbstractCar {
    @Override
    public String brand() {
        return "TowTruck";
    }

    public void tow(ICar other) {
        other.stop();
    }
}
