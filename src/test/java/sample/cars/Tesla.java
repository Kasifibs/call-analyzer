package sample.cars;

public class Tesla implements ElectricCar {
    @Override
    public void start() {
        charge();
    }

    @Override
    public void stop() {
    }

    @Override
    public String brand() {
        return "Tesla";
    }

    @Override
    public void charge() {
        System.out.println("charging");
    }
}
