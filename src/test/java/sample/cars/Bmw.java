package sample.cars;

public class Bmw extends AbstractCar {
    @Override
    public String brand() {
        return "BMW";
    }

    public void openSunroof() {
        System.out.println("sunroof open");
    }

    @Override
    public String toString() {
        return "Bmw";
    }
}
