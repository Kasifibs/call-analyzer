package sample.cars;

/** Never called from App: its calls must not show up when analysing App#main. */
public class Showroom {
    public void display(ICar car) {
        car.honk();
    }
}
