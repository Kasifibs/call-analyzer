package sample.app;

public class SimpleRepository implements IRepository {
    public String get() {
        return "simple";
    }

    public void save(String s) {
    }
}
