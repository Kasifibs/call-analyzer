package sample.cars;

public class Dealer {
    private final Garage garage = new Garage();
    private final TowTruck towTruck = new TowTruck();
    private final Bicycle bicycle = new Bicycle();

    public void sell(Bmw bmw) {
        bmw.openSunroof();
        garage.service(bmw);
        towTruck.tow(bmw);
        bicycle.start();
        pingPong(2);
    }

    private void pingPong(int n) {
        if (n > 0) pongPing(n - 1);
    }

    private void pongPing(int n) {
        if (n > 0) pingPong(n - 1);
    }
}
