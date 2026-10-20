package sample.cars;

import java.util.List;

public class App {
    public static void main(String[] args) {
        Dealer dealer = new Dealer();
        dealer.sell(new Bmw());

        Garage garage = new Garage();
        garage.serviceAll(List.of(new Tesla(), new Bmw()));
        garage.describe("just a string");
    }
}
