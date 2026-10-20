package sample.app;

public class ServiceInterfaceImpl implements IServiceInterface {
    public void handle(String s, int i) {
        System.out.println(s + i);
    }
}
