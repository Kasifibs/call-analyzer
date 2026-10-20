package sample.app;

import java.util.Optional;

public class SimpleService2 {

    private final SimpleRepository2 simpleRepository2;

    public SimpleService2(SimpleRepository2 simpleRepository2) {
        this.simpleRepository2 = simpleRepository2;
    }

    public void handle() {
        String s = simpleRepository2.get();
        System.out.println(s);
        simpleRepository2.save(s + "-");
    }
}
