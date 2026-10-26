package com.example.domain;

/** Lives in the domain module: its use of Tesla is only seen if the module is on the class path. */
public class Charger {
    private Tesla tesla;

    public void charge() {
        tesla.start();
    }
}
