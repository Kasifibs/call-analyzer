package sample.app;

public class RepositoryForInnerEventFlow implements IRepositoryForInnerEventFlow {
    public String get() {
        return "inner";
    }

    public void save(String s) {
    }
}
