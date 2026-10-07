package sample.cars;

public abstract class AbstractCar implements ICar {
    protected final Engine engine = new Engine(this);

    @Override
    public void start() {
        engine.ignite();
        log("started");
    }

    @Override
    public void stop() {
        log("stopped");
    }

    private void log(String message) {
        System.out.println(brand() + " " + message);
    }
}
