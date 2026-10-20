package com.example.app;

import com.example.domain.Bmw;
import com.example.domain.CarAcquirer;
import com.example.domain.Charger;

import java.util.List;

public class RentalService {
    private Bmw bmw;
    private Charger charger;

    public void rent() {
        CarAcquirer.acquire(List.of(bmw));
        bmw.start();
        charger.charge(); // uses Tesla inside the domain module, Tesla is not acquired
    }
}
