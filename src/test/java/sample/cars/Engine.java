package sample.cars;

public class Engine {
    private final ICar owner;

    public Engine(ICar owner) {
        this.owner = owner;
    }

    public void ignite() {
        Diagnostics.report(owner);
    }
}
